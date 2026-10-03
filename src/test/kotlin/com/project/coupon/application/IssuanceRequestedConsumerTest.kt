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
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.kafka.support.serializer.DeserializationException
import java.time.Duration
import java.util.Properties
import java.util.concurrent.TimeUnit
import kotlin.time.Duration.Companion.seconds

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class IssuanceRequestedConsumerTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val kafkaTemplate: KafkaTemplate<Any, Any>,
	private val consumerFactory: ConsumerFactory<String, Any>,
	redisTemplate: StringRedisTemplate,
) : FunSpec({

	val probe = IssuanceGateProbe(redisTemplate)

	afterTest {
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun saveCoupon(totalQuantity: Int): Long =
		couponRepository.save(
			Coupon(name = "소비", totalQuantity = totalQuantity, validityDays = 7, createdAt = FixedClockConfiguration.NOW)
		).id.shouldNotBeNull()

	fun issuedQuantity(couponId: Long): Int = couponRepository.findById(couponId).get().issuedQuantity

	// 같은 행사 ID 키는 한 파티션에서 순서대로 처리되므로, 뒤이은 요청의 반영을 앞선 재전달의 처리 완료 신호로 쓴다
	test("같은 메시지가 다시 전달돼도 발급은 1건이고 문지기 재고를 되돌리지 않는다") {
		val couponId = saveCoupon(totalQuantity = 3)
		couponService.issue(couponId, userId = 1L)
		val first = eventually(10.seconds) { issuanceRepository.findByUserIdAndCouponId(1L, couponId).shouldNotBeNull() }

		kafkaTemplate.send(
			KafkaConfig.ISSUANCE_REQUESTED_TOPIC,
			couponId.toString(),
			IssuanceRequested(first.messageId.shouldNotBeNull(), couponId, 1L),
		).get(10, TimeUnit.SECONDS)
		couponService.issue(couponId, userId = 2L)
		eventually(10.seconds) { issuanceRepository.findByUserIdAndCouponId(2L, couponId).shouldNotBeNull() }

		assertSoftly {
			issuanceRepository.count() shouldBe 2
			issuedQuantity(couponId) shouldBe 2
			probe.stock(couponId) shouldBe "1"
			probe.isMember(couponId, 1L) shouldBe true
		}
	}

	// Redis 가 사용자 기록을 잃은 뒤 들어온 요청(다른 messageId)이 재전달되는 상황을 같은 이벤트 두 번 발행으로 만든다
	test("이미 발급된 사용자의 다른 messageId 요청이 두 번 전달돼도 재고는 한 번만 되돌린다") {
		val couponId = saveCoupon(totalQuantity = 3)
		couponService.issue(couponId, userId = 1L)
		eventually(10.seconds) { issuanceRepository.findByUserIdAndCouponId(1L, couponId).shouldNotBeNull() }
		val lostReservation = IssuanceRequested("redelivered-${couponId}", couponId, 1L)

		repeat(2) {
			kafkaTemplate.send(KafkaConfig.ISSUANCE_REQUESTED_TOPIC, couponId.toString(), lostReservation)
				.get(10, TimeUnit.SECONDS)
		}
		couponService.issue(couponId, userId = 2L)
		eventually(10.seconds) { issuanceRepository.findByUserIdAndCouponId(2L, couponId).shouldNotBeNull() }

		assertSoftly {
			issuanceRepository.count() shouldBe 2
			probe.stock(couponId) shouldBe "2"
		}
	}

	test("역직렬화할 수 없는 메시지는 원본 바이트 그대로 DLT 로 옮겨지고, 같은 행사의 다음 요청은 막히지 않는다") {
		val couponId = saveCoupon(totalQuantity = 3)

		kafkaTemplate.send(KafkaConfig.ISSUANCE_REQUESTED_TOPIC, couponId.toString(), "not-an-issuance-request")
			.get(10, TimeUnit.SECONDS)
		couponService.issue(couponId, userId = 1L)

		eventually(10.seconds) { issuanceRepository.findByUserIdAndCouponId(1L, couponId).shouldNotBeNull() }
		val properties = Properties().apply {
			put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest")
			put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer::class.java)
			put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer::class.java)
		}
		consumerFactory.createConsumer("dlt-test-$couponId", null, null, properties).use { consumer ->
			consumer.subscribe(listOf(KafkaConfig.ISSUANCE_REQUESTED_DLT))
			val deadLetter = eventually(10.seconds) {
				consumer.poll(Duration.ofMillis(200)).firstOrNull { it.key() == couponId.toString() }.shouldNotBeNull()
			}
			assertSoftly {
				String(deadLetter.value() as ByteArray) shouldBe "\"not-an-issuance-request\""
				String(deadLetter.headers().lastHeader(KafkaHeaders.DLT_EXCEPTION_FQCN).value()) shouldBe
					DeserializationException::class.java.name
			}
		}
	}
})
