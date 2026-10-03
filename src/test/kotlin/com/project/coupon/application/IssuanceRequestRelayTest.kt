package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.domain.PendingIssuanceRequestRepository
import com.project.coupon.support.IssuanceBusyException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.jdbc.core.JdbcTemplate
import org.springframework.kafka.core.KafkaTemplate
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicReference
import kotlin.time.Duration.Companion.seconds

// 발행을 다음 한 번만 일부러 실패시켜, 실패한 발행이 저장됐다가 릴레이로 정확히 한 번 반영되는지 본다
@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class, IssuanceRequestRelayTest.FlakyPublisherConfiguration::class)
class IssuanceRequestRelayTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val pendingIssuanceRequestRepository: PendingIssuanceRequestRepository,
	private val publisher: FlakyIssuanceRequestPublisher,
	private val jdbcTemplate: JdbcTemplate,
	redisTemplate: StringRedisTemplate,
) : FunSpec({

	val probe = IssuanceGateProbe(redisTemplate)

	afterTest {
		issuanceRepository.deleteAll()
		pendingIssuanceRequestRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun saveCoupon(totalQuantity: Int): Long =
		couponRepository.save(
			Coupon(name = "재발행", totalQuantity = totalQuantity, validityDays = 7, createdAt = FixedClockConfiguration.NOW)
		).id.shouldNotBeNull()

	test("발행이 실패해도 재발행 대기로 저장해 접수하고, 릴레이가 같은 messageId 로 다시 보내 정확히 1건 반영한다") {
		val couponId = saveCoupon(totalQuantity = 3)
		publisher.failNext(FlakyIssuanceRequestPublisher.Failure.NOT_SENT)

		couponService.issue(couponId, userId = 1L)

		assertSoftly {
			probe.stock(couponId) shouldBe "2"
			probe.isMember(couponId, 1L) shouldBe true
		}
		eventually(15.seconds) {
			assertSoftly {
				issuanceRepository.findByUserIdAndCouponId(1L, couponId).shouldNotBeNull()
				pendingIssuanceRequestRepository.count() shouldBe 0
			}
		}
		probe.stock(couponId) shouldBe "2"
	}

	test("실제로는 보내졌는데 실패로 보인 발행도 재발행되지만, 같은 messageId 라 발급은 1건이고 재고도 어긋나지 않는다") {
		val couponId = saveCoupon(totalQuantity = 3)
		publisher.failNext(FlakyIssuanceRequestPublisher.Failure.SENT_BUT_UNCONFIRMED)

		couponService.issue(couponId, userId = 1L)
		eventually(15.seconds) { pendingIssuanceRequestRepository.count() shouldBe 0 }
		// 같은 행사 키는 한 파티션에서 순서대로 처리되므로, 재발행 뒤에 보낸 요청의 반영이 앞선 중복분 처리 완료의 신호다
		couponService.issue(couponId, userId = 2L)
		eventually(15.seconds) { issuanceRepository.findByUserIdAndCouponId(2L, couponId).shouldNotBeNull() }

		assertSoftly {
			issuanceRepository.count() shouldBe 2
			couponRepository.findById(couponId).get().issuedQuantity shouldBe 2
			probe.stock(couponId) shouldBe "1"
			probe.isMember(couponId, 1L) shouldBe true
		}
	}

	// 저장 실패를 만들려고 대기 테이블 이름을 잠시 바꾼다 — finally 에서 되돌린다
	test("발행도 실패하고 재발행 대기 저장도 실패하면 선점을 해제하고 503 ISSUANCE_BUSY 로 거절한다") {
		val couponId = saveCoupon(totalQuantity = 3)
		publisher.failNext(FlakyIssuanceRequestPublisher.Failure.NOT_SENT)
		jdbcTemplate.execute("rename table pending_issuance_request to pending_issuance_request_off")
		try {
			shouldThrow<IssuanceBusyException> { couponService.issue(couponId, userId = 1L) }
		} finally {
			jdbcTemplate.execute("rename table pending_issuance_request_off to pending_issuance_request")
		}

		assertSoftly {
			probe.stock(couponId) shouldBe "3"
			probe.isMember(couponId, 1L) shouldBe false
			pendingIssuanceRequestRepository.count() shouldBe 0
		}
	}
}) {

	@TestConfiguration(proxyBeanMethods = false)
	class FlakyPublisherConfiguration {
		@Bean
		@Primary
		fun flakyIssuanceRequestPublisher(kafkaTemplate: KafkaTemplate<String, IssuanceRequested>) =
			FlakyIssuanceRequestPublisher(kafkaTemplate)
	}
}

class FlakyIssuanceRequestPublisher(
	kafkaTemplate: KafkaTemplate<String, IssuanceRequested>,
) : IssuanceRequestPublisher(kafkaTemplate) {

	enum class Failure { NONE, NOT_SENT, SENT_BUT_UNCONFIRMED }

	private val next = AtomicReference(Failure.NONE)

	fun failNext(failure: Failure) {
		next.set(failure)
	}

	override fun publish(event: IssuanceRequested) {
		when (next.getAndSet(Failure.NONE)) {
			Failure.NONE -> super.publish(event)
			Failure.NOT_SENT -> throw TimeoutException("테스트: 발행 실패")
			Failure.SENT_BUT_UNCONFIRMED -> {
				super.publish(event)
				throw TimeoutException("테스트: 보냈지만 확인 못 함")
			}
		}
	}
}
