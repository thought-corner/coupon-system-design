package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.AlreadyIssuedException
import io.kotest.assertions.assertSoftly
import io.kotest.assertions.nondeterministic.eventually
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate
import kotlin.time.Duration.Companion.seconds

// 문지기(Redis)와 원본(DB)이 어긋난 상황을 직접 만들어, 비동기 반영이 DB 기준으로 맞게 끝나고 문지기가 보상되는지 본다
@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class CouponIssueGateFlowTest(
	private val couponService: CouponService,
	private val issuanceGate: IssuanceGate,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	redisTemplate: StringRedisTemplate,
) : FunSpec({

	val probe = IssuanceGateProbe(redisTemplate)

	afterTest {
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun saveCoupon(totalQuantity: Int, issuedQuantity: Int = 0): Long =
		couponRepository.save(
			Coupon(
				name = "선착순",
				totalQuantity = totalQuantity,
				issuedQuantity = issuedQuantity,
				validityDays = 7,
				createdAt = FixedClockConfiguration.NOW,
			)
		).id.shouldNotBeNull()

	fun issuedQuantity(couponId: Long): Int = couponRepository.findById(couponId).get().issuedQuantity

	suspend fun awaitIssuanceCount(expected: Long) {
		eventually(5.seconds) { issuanceRepository.count() shouldBe expected }
	}

	test("행사를 만들 때는 문지기를 건드리지 않고, 첫 발급 요청이 DB 기준으로 채운다") {
		val couponId = couponService.createCoupon(CreateCouponRequest(name = "선착순")).id.shouldNotBeNull()
		val before = probe.stock(couponId)

		couponService.issue(couponId, userId = 1L)

		assertSoftly {
			before shouldBe null
			probe.stock(couponId) shouldBe "9999"
		}
		awaitIssuanceCount(1)
	}

	test("DB 에서 그 밖의 이유로 실패하면 문지기 통과를 되돌린다 — 재고 +1, 사용자 제거") {
		val ghostCouponId = Long.MAX_VALUE - 1
		issuanceGate.initialize(ghostCouponId, remaining = 5, issuedUserIds = emptyList())
		try {
			couponService.issue(ghostCouponId, userId = 1L)

			eventually(5.seconds) {
				assertSoftly {
					probe.stock(ghostCouponId) shouldBe "5"
					probe.isMember(ghostCouponId, 1L) shouldBe false
				}
			}
		} finally {
			probe.clear(ghostCouponId)
		}
	}

	test("문지기 키가 없으면 첫 요청이 DB 에서 남은 수량과 발급받은 사용자를 복원한 뒤 판정한다") {
		val couponId = saveCoupon(totalQuantity = 3)
		couponService.issue(couponId, userId = 1L)
		awaitIssuanceCount(1)
		probe.clear(couponId)

		couponService.issue(couponId, userId = 2L)

		assertSoftly {
			probe.stock(couponId) shouldBe "1"
			shouldThrow<AlreadyIssuedException> { couponService.issue(couponId, userId = 1L) }
		}
		eventually(5.seconds) { issuedQuantity(couponId) shouldBe 2 }
	}

	test("문지기의 사용자 기록이 사라져 통과했어도 DB 가 중복을 막고, 재고는 되돌리되 사용자 기록은 남긴다") {
		val couponId = saveCoupon(totalQuantity = 3)
		couponService.issue(couponId, userId = 1L)
		awaitIssuanceCount(1)
		probe.forgetUser(couponId, userId = 1L)

		couponService.issue(couponId, userId = 1L)

		eventually(5.seconds) {
			assertSoftly {
				probe.stock(couponId) shouldBe "2"
				probe.isMember(couponId, 1L) shouldBe true
			}
		}
		assertSoftly {
			issuanceRepository.count() shouldBe 1
			issuedQuantity(couponId) shouldBe 1
		}
	}

	test("문지기 재고가 DB 보다 많아 통과했어도 DB 가 매진을 막고, 문지기를 매진으로 맞춘다") {
		val couponId = saveCoupon(totalQuantity = 1, issuedQuantity = 1)
		issuanceGate.initialize(couponId, remaining = 5, issuedUserIds = emptyList())

		couponService.issue(couponId, userId = 2L)

		eventually(5.seconds) {
			assertSoftly {
				probe.stock(couponId) shouldBe "0"
				probe.isMember(couponId, 2L) shouldBe false
			}
		}
		assertSoftly {
			issuanceRepository.count() shouldBe 0
			issuedQuantity(couponId) shouldBe 1
		}
	}
})
