package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.runConcurrently
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class CouponIssueConcurrencyTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest {
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun createCoupon(totalQuantity: Int): Long =
		couponRepository.save(
			Coupon(
				name = "선착순",
				totalQuantity = totalQuantity,
				validityDays = 7,
				createdAt = FixedClockConfiguration.NOW,
			)
		).id.shouldNotBeNull()

	// 결과마다 성공이면 null, 실패면 그 예외
	fun issueConcurrently(userIds: List<Long>, couponId: Long, threads: Int = userIds.size): List<Throwable?> =
		runConcurrently(userIds, threads) { userId -> couponService.issue(couponId, userId) }
			.map { it.exceptionOrNull() }

	fun issuedQuantity(couponId: Long): Int = couponRepository.findById(couponId).get().issuedQuantity

	test("서로 다른 사용자 100명이 총수량 10 인 행사에 동시에 요청하면 정확히 10건만 발급되고 발급 수량과 발급 내역 수가 같다") {
		val couponId = createCoupon(totalQuantity = 10)

		val results = issueConcurrently((1L..100L).toList(), couponId)

		assertSoftly {
			results.count { it == null } shouldBe 10
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("SoldOutException")
			issuanceRepository.count() shouldBe 10
			issuedQuantity(couponId) shouldBe 10
		}
	}

	test("서로 다른 사용자 5,000명이 총수량 1,000 인 행사에 동시에 요청하면 정확히 1,000건만 발급되고 발급 수량과 발급 내역 수가 같다") {
		val couponId = createCoupon(totalQuantity = 1_000)

		val results = issueConcurrently((1L..5_000L).toList(), couponId, threads = 200)

		assertSoftly {
			results.size shouldBe 5_000
			results.count { it == null } shouldBe 1_000
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("SoldOutException")
			issuanceRepository.count() shouldBe 1_000
			issuedQuantity(couponId) shouldBe 1_000
		}
	}

	test("같은 사용자가 같은 행사에 동시에 20번 요청하면 1건만 발급되고 나머지는 모두 AlreadyIssuedException 이다") {
		val couponId = createCoupon(totalQuantity = 10_000)

		val results = issueConcurrently(List(20) { 42L }, couponId)

		assertSoftly {
			results.count { it == null } shouldBe 1
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("AlreadyIssuedException")
			issuanceRepository.count() shouldBe 1
			issuedQuantity(couponId) shouldBe 1
		}
	}
})
