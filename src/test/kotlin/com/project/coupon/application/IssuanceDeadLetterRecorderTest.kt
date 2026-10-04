package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.DeadLetterStatus
import com.project.coupon.domain.IssuanceDeadLetterArrivalRepository
import com.project.coupon.domain.IssuanceDeadLetterRepository
import com.project.coupon.support.KafkaConfig
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

// DLT 로 옮겨진 것처럼 DLT 토픽에 직접 보내, 기록·누적·알림 규칙을 본다
// 고정 시계에서 재처리 지연(기본 1분)이 지나지 않으므로 이 스펙에서는 자동 재처리가 돌지 않는다 — 알림은 10초 주기 전송 작업이 보낸다
@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class, IssuanceDeadLetterRecorderTest.RecordingAlerterConfiguration::class)
class IssuanceDeadLetterRecorderTest(
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val arrivalRepository: IssuanceDeadLetterArrivalRepository,
	private val recorder: IssuanceDeadLetterRecorder,
	private val kafkaTemplate: KafkaTemplate<Any, Any>,
	private val alerter: RecordingDeadLetterAlerter,
) : FunSpec({

	afterTest {
		arrivalRepository.deleteAll()
		deadLetterRepository.deleteAll()
		alerter.messages.clear()
	}

	fun sendToDeadLetter(value: Any?, key: String, replayAttempt: Int? = null) {
		val record = ProducerRecord<Any?, Any?>(KafkaConfig.ISSUANCE_REQUESTED_DLT, key, value).apply {
			headers().add(KafkaHeaders.DLT_EXCEPTION_FQCN, "org.springframework.dao.DataAccessResourceFailureException".toByteArray())
			headers().add(KafkaHeaders.DLT_EXCEPTION_MESSAGE, "DB 연결 실패".toByteArray())
			replayAttempt?.let { headers().add(KafkaConfig.REPLAY_ATTEMPT_HEADER, it.toString().toByteArray()) }
		}
		// 값이 없는 레코드도 보내야 해서 널을 허용하는 레코드를 그대로 넘긴다
		@Suppress("UNCHECKED_CAST")
		kafkaTemplate.send(record as ProducerRecord<Any, Any>).get(10, TimeUnit.SECONDS)
	}

	// 고정 시계라 재처리 작업이 돌지 않으므로, 재처리 작업이 n 번째 재처리를 시작한 상태를 직접 만든다
	fun startReplay(messageId: String, attempt: Int) {
		val deadLetter = deadLetterRepository.findByMessageId(messageId).shouldNotBeNull()
		deadLetter.status = DeadLetterStatus.REPLAYING
		deadLetter.replayAttempts = attempt
		deadLetterRepository.save(deadLetter)
	}

	suspend fun awaitStatus(messageId: String, status: DeadLetterStatus) {
		eventually(10.seconds) { deadLetterRepository.findByMessageId(messageId).shouldNotBeNull().status shouldBe status }
	}

	test("처음 DLT 로 온 발급 요청은 재처리 대기로 기록되고 알림은 보내지 않는다") {
		val messageId = UUID.randomUUID().toString()

		sendToDeadLetter(IssuanceRequested(messageId, couponId = 7L, userId = 1L), key = "7")

		val deadLetter = eventually(10.seconds) { deadLetterRepository.findByMessageId(messageId).shouldNotBeNull() }
		assertSoftly {
			deadLetter.status shouldBe DeadLetterStatus.PENDING_REPLAY
			deadLetter.deadLetterCount shouldBe 1
			deadLetter.couponId shouldBe 7L
			deadLetter.userId shouldBe 1L
			deadLetter.failureType shouldBe "org.springframework.dao.DataAccessResourceFailureException"
			alerter.messages.shouldBeEmpty()
		}
	}

	test("재처리가 한도만큼 실패하면 알림 상태가 되어 한 번만 알리고, 그 뒤에 늦은 사본이 와도 다시 알리지 않는다") {
		val messageId = UUID.randomUUID().toString()
		val event = IssuanceRequested(messageId, couponId = 8L, userId = 1L)
		sendToDeadLetter(event, key = "8")
		awaitStatus(messageId, DeadLetterStatus.PENDING_REPLAY)

		(1..IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS).forEach { attempt ->
			startReplay(messageId, attempt)
			sendToDeadLetter(event, key = "8", replayAttempt = attempt)
			val expected = if (attempt < IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS) DeadLetterStatus.PENDING_REPLAY else DeadLetterStatus.ALERTED
			awaitStatus(messageId, expected)
		}

		eventually(30.seconds) {
			assertSoftly {
				alerter.messages shouldHaveSize 1
				deadLetterRepository.findByMessageId(messageId).shouldNotBeNull().alertedAt.shouldNotBeNull()
			}
		}
		alerter.messages.single() shouldContain messageId

		sendToDeadLetter(event, key = "8", replayAttempt = IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS)

		eventually(10.seconds) {
			deadLetterRepository.findByMessageId(messageId).shouldNotBeNull().deadLetterCount shouldBe
				IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 2
		}
		alerter.messages shouldHaveSize 1
	}

	// 10분 회수로 같은 재처리의 사본이 두 개 나가 둘 다 돌아오는 상황 — 늦은 사본을 다음 재처리의 실패로 세면 한도에 일찍 닿는다
	test("이전 재처리의 늦은 사본이나 원본의 중복 도착은 도착 횟수만 늘리고, 진행 중인 재처리의 상태와 실패 횟수는 바꾸지 않는다") {
		val messageId = UUID.randomUUID().toString()
		val event = IssuanceRequested(messageId, couponId = 13L, userId = 1L)
		sendToDeadLetter(event, key = "13")
		awaitStatus(messageId, DeadLetterStatus.PENDING_REPLAY)
		startReplay(messageId, attempt = 1)
		sendToDeadLetter(event, key = "13", replayAttempt = 1)
		awaitStatus(messageId, DeadLetterStatus.PENDING_REPLAY)
		startReplay(messageId, attempt = 2)

		sendToDeadLetter(event, key = "13", replayAttempt = 1)
		sendToDeadLetter(event, key = "13")

		eventually(10.seconds) { deadLetterRepository.findByMessageId(messageId).shouldNotBeNull().deadLetterCount shouldBe 4 }
		val deadLetter = deadLetterRepository.findByMessageId(messageId).shouldNotBeNull()
		assertSoftly {
			deadLetter.status shouldBe DeadLetterStatus.REPLAYING
			deadLetter.replayAttempts shouldBe 2
			deadLetter.replayFailures shouldBe 1
		}

		sendToDeadLetter(event, key = "13", replayAttempt = 2)

		awaitStatus(messageId, DeadLetterStatus.PENDING_REPLAY)
		val afterSecondFailure = deadLetterRepository.findByMessageId(messageId).shouldNotBeNull()
		assertSoftly {
			afterSecondFailure.deadLetterCount shouldBe 5
			afterSecondFailure.replayFailures shouldBe 2
		}
	}

	test("발급 요청으로 읽을 수 없는 메시지는 읽을 수 없음으로 기록하고 바로 알림을 보낸다") {
		sendToDeadLetter("not-an-issuance-request", key = "9")

		eventually(30.seconds) { alerter.messages shouldHaveSize 1 }
		val unreadable = deadLetterRepository.findAll().single()
		assertSoftly {
			unreadable.status shouldBe DeadLetterStatus.UNREADABLE
			unreadable.couponId shouldBe 9L
			unreadable.messageId.shouldBeNull()
			alerter.messages.single() shouldContain "읽을 수 없는"
		}
	}

	// 항상 실패하는 레코드가 DLT 파티션을 멈추면 뒤의 실제 실패 건이 기록되지 않는다 — 내용 결함은 실패 대신 기록으로 흡수한다
	test("값이 없거나 messageId 가 형식에 맞지 않는 메시지도 읽을 수 없음으로 기록되고, 같은 행사의 다음 메시지는 막히지 않는다") {
		val next = UUID.randomUUID().toString()

		sendToDeadLetter(null, key = "10")
		sendToDeadLetter(IssuanceRequested("x".repeat(64), couponId = 10L, userId = 1L), key = "10")
		sendToDeadLetter(IssuanceRequested(next, couponId = 10L, userId = 2L), key = "10")

		eventually(30.seconds) {
			assertSoftly {
				deadLetterRepository.findByMessageId(next).shouldNotBeNull()
				deadLetterRepository.findAll().count { it.status == DeadLetterStatus.UNREADABLE } shouldBe 2
				alerter.messages shouldHaveSize 2
			}
		}
	}

	test("같은 DLT 위치의 메시지가 다시 전달되면 횟수를 늘리지 않는다") {
		val messageId = UUID.randomUUID().toString()
		val arrival = DeadLetterArrival(
			key = "11",
			payload = """{"messageId":"$messageId","couponId":11,"userId":1}""".toByteArray(),
			partition = 0,
			offset = Long.MAX_VALUE,
			exceptionClass = "java.lang.IllegalStateException",
			exceptionMessage = null,
		)

		recorder.record(arrival)
		recorder.record(arrival)

		deadLetterRepository.findByMessageId(messageId).shouldNotBeNull().deadLetterCount shouldBe 1
	}

	// 토픽을 다시 만들면 오프셋이 0 부터 다시 매겨진다 — 위치만 같고 내용이 다른 메시지를 이미 본 것으로 버리면 안 된다
	test("같은 DLT 위치라도 내용이 다른 메시지는 새 도착으로 기록한다") {
		val first = UUID.randomUUID().toString()
		val second = UUID.randomUUID().toString()

		listOf(first, second).forEach { messageId ->
			recorder.record(
				DeadLetterArrival(
					key = "12",
					payload = """{"messageId":"$messageId","couponId":12,"userId":1}""".toByteArray(),
					partition = 0,
					offset = Long.MAX_VALUE - 1,
					exceptionClass = "java.lang.IllegalStateException",
					exceptionMessage = null,
				)
			)
		}

		assertSoftly {
			deadLetterRepository.findByMessageId(first).shouldNotBeNull()
			deadLetterRepository.findByMessageId(second).shouldNotBeNull()
		}
	}
}) {

	@TestConfiguration(proxyBeanMethods = false)
	class RecordingAlerterConfiguration {
		@Bean
		@Primary
		fun recordingDeadLetterAlerter() = RecordingDeadLetterAlerter()
	}
}

class RecordingDeadLetterAlerter : DeadLetterAlerter {
	val messages = CopyOnWriteArrayList<String>()

	override fun alert(message: String): Boolean {
		messages += message
		return true
	}
}
