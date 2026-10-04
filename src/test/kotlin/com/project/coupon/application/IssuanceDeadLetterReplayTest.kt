package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.awaitListenersAssigned
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.DeadLetterStatus
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceDeadLetter
import com.project.coupon.domain.IssuanceDeadLetterArrivalRepository
import com.project.coupon.domain.IssuanceDeadLetterRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.KafkaConfig
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.dao.DataAccessResourceFailureException
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.KafkaException
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.core.KafkaTemplate
import java.time.Clock
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.time.Duration.Companion.seconds

// DB 쓰기를 일부러 실패시켜 컨슈머 재시도 → DLT → 기록 → 자동 재처리까지 실제 흐름으로 본다
// 고정 시계에서도 재처리가 돌도록 재처리 지연을 0 으로 둔다
@SpringBootTest(properties = ["coupon.dead-letter.replay-delay=0s"])
@Import(
	TestcontainersConfiguration::class,
	FixedClockConfiguration::class,
	IssuanceDeadLetterReplayTest.FailingWriterConfiguration::class,
)
class IssuanceDeadLetterReplayTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val arrivalRepository: IssuanceDeadLetterArrivalRepository,
	private val writer: FailingIssuanceWriter,
	private val alerter: RecordingDeadLetterAlerter,
	private val publisher: ControllableReplayPublisher,
	private val listenerRegistry: KafkaListenerEndpointRegistry,
	redisTemplate: StringRedisTemplate,
) : FunSpec({

	val probe = IssuanceGateProbe(redisTemplate)

	beforeSpec {
		awaitListenersAssigned(listenerRegistry, partitionsPerTopic = KafkaConfig.ISSUANCE_PARTITIONS)
	}

	afterTest {
		arrivalRepository.deleteAll()
		deadLetterRepository.deleteAll()
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
		alerter.messages.clear()
		publisher.release()
	}

	fun saveCoupon(totalQuantity: Int): Long =
		couponRepository.save(
			Coupon(name = "재처리", totalQuantity = totalQuantity, validityDays = 7, createdAt = FixedClockConfiguration.NOW)
		).id.shouldNotBeNull()

	test("DB 장애로 재시도를 다 쓴 발급 요청은 DLT 에 기록되고, 장애가 풀린 뒤 자동 재처리로 정확히 1건 반영된다") {
		val couponId = saveCoupon(totalQuantity = 3)
		// 첫 전달 + 컨슈머 재시도 3회가 모두 실패해야 DLT 로 간다
		writer.failNext(couponId, times = 4)

		couponService.issue(couponId, userId = 1L)

		val issuance = eventually(30.seconds) { issuanceRepository.findByUserIdAndCouponId(1L, couponId).shouldNotBeNull() }
		val deadLetter = eventually(10.seconds) {
			deadLetterRepository.findByMessageId(issuance.messageId.shouldNotBeNull()).shouldNotBeNull()
				.also { it.status shouldBe DeadLetterStatus.RESOLVED }
		}
		assertSoftly {
			deadLetter.deadLetterCount shouldBe 1
			deadLetter.failureType shouldBe DataAccessResourceFailureException::class.java.name
			issuanceRepository.count() shouldBe 1
			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1
			probe.stock(couponId) shouldBe "2"
			alerter.messages.size shouldBe 0
		}
	}

	// 재처리 발행 직후 재처리 작업이 (뒤 건 발행이 느려) 아직 끝나지 않은 상황 — 그동안 컨슈머가 결과 기록에서 멈추면 그 행사의 반영 전체가 밀린다
	test("재처리 작업이 발행 뒤 아직 끝나지 않았어도 컨슈머는 기다리지 않고 재처리 결과를 기록한다") {
		val couponId = saveCoupon(totalQuantity = 3)
		writer.failNext(couponId, times = 4)
		publisher.holdAfterReplay()

		couponService.issue(couponId, userId = 1L)

		val deadLetter = eventually(30.seconds) {
			deadLetterRepository.findAll().single { it.couponId == couponId }
				.also { it.status shouldBe DeadLetterStatus.RESOLVED }
		}
		assertSoftly {
			publisher.isHolding() shouldBe true
			deadLetter.deadLetterCount shouldBe 1
			issuanceRepository.count() shouldBe 1
		}
	}

	// 발행조차 못 한 재처리는 실패가 아니다 — 브로커 장애가 길어져도 실제 재처리 기회를 잃지 않아야 한다
	test("재처리 발행이 실패해 되돌린 건은 재처리 실패 횟수에 들어가지 않는다") {
		val couponId = saveCoupon(totalQuantity = 3)
		writer.failNext(couponId, times = 4)
		publisher.failNextReplays(IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 1)

		couponService.issue(couponId, userId = 1L)

		val deadLetter = eventually(30.seconds) {
			deadLetterRepository.findAll().single { it.couponId == couponId }
				.also { it.status shouldBe DeadLetterStatus.RESOLVED }
		}
		assertSoftly {
			deadLetter.replayFailures shouldBe 0
			issuanceRepository.count() shouldBe 1
			alerter.messages.size shouldBe 0
		}
	}

	// 브로커엔 들어갔는데 확인 응답만 늦어 실패로 보인 재처리 — 되돌려지지만 실제로 나간 시도의 실패는 돌아오는 대로 세야 한도가 지켜진다
	test("확인 응답만 늦어 실패로 보인 재처리도 실제로 실패해 돌아오면 횟수에 들어가, 한도에서 알림 상태로 멈춘다") {
		val couponId = saveCoupon(totalQuantity = 3)
		writer.failNext(couponId, times = Int.MAX_VALUE)
		publisher.failAfterSendingNextReplays(2)

		couponService.issue(couponId, userId = 1L)

		val deadLetter = eventually(90.seconds) {
			deadLetterRepository.findAll().single { it.couponId == couponId }
				.also { it.alertedAt.shouldNotBeNull() }
		}
		assertSoftly {
			deadLetter.status shouldBe DeadLetterStatus.ALERTED
			deadLetter.replayFailures shouldBe IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS
			alerter.messages shouldHaveSize 1
			issuanceRepository.count() shouldBe 0
		}
	}

	test("재처리를 한도만큼 해도 계속 실패하면 알림 상태로 멈추고 관리자에게 한 번 알리며, 선점은 그대로 둔다") {
		val couponId = saveCoupon(totalQuantity = 3)
		writer.failNext(couponId, times = Int.MAX_VALUE)

		couponService.issue(couponId, userId = 1L)

		val deadLetter = eventually(90.seconds) {
			deadLetterRepository.findAll().single { it.couponId == couponId }
				.also { it.alertedAt.shouldNotBeNull() }
		}
		assertSoftly {
			deadLetter.status shouldBe DeadLetterStatus.ALERTED
			deadLetter.deadLetterCount shouldBe IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 1
			deadLetter.replayFailures shouldBe IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS
			alerter.messages shouldHaveSize 1
			alerter.messages.single() shouldContain "재처리 ${IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS}회 실패"
			issuanceRepository.count() shouldBe 0
			probe.stock(couponId) shouldBe "2"
			probe.isMember(couponId, 1L) shouldBe true
		}
	}

	// 늦게 돌아온 이전 시도의 실패로 한도에 닿았는데, 진행 중이던 시도는 발행 실패로 되돌아온 상태 — 이걸 알림으로 넘길 도착은 더 오지 않는다
	test("실패 수가 이미 한도에 닿은 재처리 대기 건은 더 재처리하지 않고 알림 상태로 넘긴다") {
		val couponId = saveCoupon(totalQuantity = 3)
		val stuck = deadLetterRepository.save(
			IssuanceDeadLetter(
				messageId = UUID.randomUUID().toString(),
				couponId = couponId,
				userId = 1L,
				payload = ByteArray(0),
				failureType = DataAccessResourceFailureException::class.java.name,
				failureReason = "한도 도달",
				status = DeadLetterStatus.PENDING_REPLAY,
				deadLetterCount = IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 1,
				replayAttempts = IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 1,
				replayFailures = IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS,
				createdAt = FixedClockConfiguration.NOW,
				updatedAt = FixedClockConfiguration.NOW,
			)
		)

		eventually(30.seconds) {
			assertSoftly {
				deadLetterRepository.findById(checkNotNull(stuck.id)).get().status shouldBe DeadLetterStatus.ALERTED
				alerter.messages shouldHaveSize 1
			}
		}
		assertSoftly {
			deadLetterRepository.findById(checkNotNull(stuck.id)).get().replayAttempts shouldBe IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS + 1
			alerter.messages.single() shouldContain "재처리 ${IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS}회 실패"
			issuanceRepository.count() shouldBe 0
		}
	}

	// 보상 마커(보존 기간 × 2)가 사라진 뒤 재처리하면 재고를 두 번 되돌릴 수 있어, 보존 기간이 지난 건은 사람에게 넘긴다
	test("보존 기간이 지난 DLT 기록은 자동 재처리하지 않고 알림 상태로 넘긴다") {
		val couponId = saveCoupon(totalQuantity = 3)
		val longAgo = FixedClockConfiguration.NOW.minus(IssuanceDeadLetterReplayer.REPLAY_WINDOW).minusDays(1)
		val stale = deadLetterRepository.save(
			IssuanceDeadLetter(
				messageId = UUID.randomUUID().toString(),
				couponId = couponId,
				userId = 1L,
				payload = ByteArray(0),
				failureType = DataAccessResourceFailureException::class.java.name,
				failureReason = "오래된 실패",
				status = DeadLetterStatus.PENDING_REPLAY,
				deadLetterCount = 1,
				createdAt = longAgo,
				updatedAt = longAgo,
			)
		)

		eventually(30.seconds) {
			assertSoftly {
				deadLetterRepository.findById(checkNotNull(stale.id)).get().status shouldBe DeadLetterStatus.ALERTED
				alerter.messages shouldHaveSize 1
			}
		}
		assertSoftly {
			alerter.messages.single() shouldContain "재처리 기한 초과"
			issuanceRepository.count() shouldBe 0
		}
	}
}) {

	@TestConfiguration(proxyBeanMethods = false)
	class FailingWriterConfiguration {
		@Bean
		@Primary
		fun failingIssuanceWriter(couponRepository: CouponRepository, issuanceRepository: IssuanceRepository, clock: Clock) =
			FailingIssuanceWriter(couponRepository, issuanceRepository, clock)

		@Bean
		@Primary
		fun recordingDeadLetterAlerter() = RecordingDeadLetterAlerter()

		@Bean
		@Primary
		fun controllableReplayPublisher(kafkaTemplate: KafkaTemplate<String, IssuanceRequested>) = ControllableReplayPublisher(kafkaTemplate)
	}
}

// 재처리 발행을 일부러 실패시키거나, 보낸 뒤 풀어 줄 때까지 돌아오지 않게 한다 — 브로커 장애와 배치 뒤 건의 느린 발행을 만든다
open class ControllableReplayPublisher(
	kafkaTemplate: KafkaTemplate<String, IssuanceRequested>,
) : IssuanceRequestPublisher(kafkaTemplate) {

	@Volatile
	private var gate: CountDownLatch? = null

	@Volatile
	private var holding = false

	private val remainingReplayFailures = AtomicInteger(0)

	private val remainingUnconfirmedReplays = AtomicInteger(0)

	fun failNextReplays(times: Int) {
		remainingReplayFailures.set(times)
	}

	fun failAfterSendingNextReplays(times: Int) {
		remainingUnconfirmedReplays.set(times)
	}

	fun holdAfterReplay() {
		gate = CountDownLatch(1)
	}

	fun isHolding(): Boolean = holding

	fun release() {
		gate?.countDown()
		gate = null
		remainingReplayFailures.set(0)
		remainingUnconfirmedReplays.set(0)
	}

	override fun publishReplay(event: IssuanceRequested, deadLetterId: Long, attempt: Int) {
		if (remainingReplayFailures.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
			throw KafkaException("테스트용 브로커 장애")
		}
		super.publishReplay(event, deadLetterId, attempt)
		if (remainingUnconfirmedReplays.getAndUpdate { if (it > 0) it - 1 else 0 } > 0) {
			throw KafkaException("테스트용 확인 응답 지연")
		}
		val current = gate ?: return
		holding = true
		try {
			current.await(HOLD_SECONDS, TimeUnit.SECONDS)
		} finally {
			holding = false
		}
	}

	companion object {
		private const val HOLD_SECONDS = 40L
	}
}

// 트랜잭션 프록시가 하위 클래스를 만들 수 있게 open 으로 둔다
open class FailingIssuanceWriter(
	couponRepository: CouponRepository,
	issuanceRepository: IssuanceRepository,
	clock: Clock,
) : IssuanceWriter(couponRepository, issuanceRepository, clock) {

	private val remainingFailures = ConcurrentHashMap<Long, Int>()

	fun failNext(couponId: Long, times: Int) {
		remainingFailures[couponId] = times
	}

	override fun write(event: IssuanceRequested): Issuance {
		if ((remainingFailures.merge(event.couponId, -1, Int::plus) ?: -1) >= 0) {
			throw DataAccessResourceFailureException("테스트용 DB 장애")
		}
		return super.write(event)
	}
}
