package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.KafkaConfig
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldMatch
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.kafka.core.ConsumerFactory
import tools.jackson.databind.json.JsonMapper
import java.time.Duration
import java.util.Properties
import kotlin.time.Duration.Companion.seconds

// 발행만 본다 — 메시지가 행사 ID 를 키로, messageId 를 담아 토픽에 남는지 직접 읽어 확인한다
@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class IssuanceRequestPublishTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val consumerFactory: ConsumerFactory<String, Any>,
) : FunSpec({

	// 컨슈머가 같은 메시지를 DB 에 반영하므로, 반영이 끝난 뒤 지워야 다른 스펙의 건수 단언과 섞이지 않는다
	afterTest {
		eventually(10.seconds) { issuanceRepository.count() shouldBe 2 }
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun readRecordsFor(couponId: Long, expected: Int): List<ConsumerRecord<String, Any>> {
		val properties = Properties().apply {
			put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
			put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
		}
		consumerFactory.createConsumer("publish-test-$couponId", null, null, properties).use { consumer ->
			consumer.subscribe(listOf(KafkaConfig.ISSUANCE_REQUESTED_TOPIC))
			val found = mutableListOf<ConsumerRecord<String, Any>>()
			val deadline = System.nanoTime() + Duration.ofSeconds(10).toNanos()
			while (found.size < expected && System.nanoTime() < deadline) {
				consumer.poll(Duration.ofMillis(200)).filter { it.key() == couponId.toString() }.forEach { found += it }
			}
			return found
		}
	}

	test("발급을 접수하면 행사 ID 를 키로, 요청마다 다른 messageId 를 담은 발급 요청이 토픽에 남는다") {
		val couponId = couponRepository.save(
			Coupon(name = "발행", totalQuantity = 10, validityDays = 7, createdAt = FixedClockConfiguration.NOW)
		).id.shouldNotBeNull()

		couponService.issue(couponId, userId = 1L)
		couponService.issue(couponId, userId = 2L)

		val records = readRecordsFor(couponId, expected = 2)
		val bodies = records.map { JsonMapper.builder().build().readTree(it.value() as String) }
		assertSoftly {
			records.size shouldBe 2
			bodies.map { it["userId"].asLong() }.toSet() shouldBe setOf(1L, 2L)
			bodies.forEach { it["couponId"].asLong() shouldBe couponId }
			bodies.forEach { it["messageId"].asString() shouldMatch Regex("[0-9a-f-]{36}") }
			bodies.map { it["messageId"].asString() }.toSet().size shouldBe 2
		}
	}
})
