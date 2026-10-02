package com.project.coupon.domain

import com.project.coupon.support.InvalidCouponException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class CouponTest : FunSpec({

	fun coupon(total: Int, issued: Int) = Coupon(
		name = "가을 할인",
		totalQuantity = total,
		issuedQuantity = issued,
		validityDays = 7,
		createdAt = LocalDateTime.of(2026, 10, 3, 10, 0),
	)

	test("발급 수량이 총수량보다 하나 적으면 매진이 아니다") {
		coupon(total = 10_000, issued = 9_999).isSoldOut() shouldBe false
	}

	test("발급 수량이 총수량에 도달하면 매진이다") {
		coupon(total = 10_000, issued = 10_000).isSoldOut() shouldBe true
	}

	test("행사를 만들면 총수량 10,000매·유효일수 7일·발급 수량 0 으로 고정된다") {
		val coupon = Coupon.create(name = "가을 할인", createdAt = LocalDateTime.of(2026, 10, 3, 10, 0))

		coupon.totalQuantity shouldBe 10_000
		coupon.validityDays shouldBe 7
		coupon.issuedQuantity shouldBe 0
	}

	context("생성 입력 검증") {
		fun create(name: String = "가을 할인", totalQuantity: Int = 1, validityDays: Int = 1) = Coupon(
			name = name,
			totalQuantity = totalQuantity,
			validityDays = validityDays,
			createdAt = LocalDateTime.of(2026, 10, 3, 10, 0),
		)

		test("총수량·유효일수가 1 이고 이름이 80자면 만들어진다") {
			create(name = "가".repeat(80), totalQuantity = 1, validityDays = 1).name.length shouldBe 80
		}

		test("이름이 공백뿐이면 InvalidCouponException 이 난다") {
			shouldThrow<InvalidCouponException> { create(name = " ") }
		}

		test("이름이 81자면 InvalidCouponException 이 난다") {
			shouldThrow<InvalidCouponException> { create(name = "가".repeat(81)) }
		}

		test("총수량이 0 이면 InvalidCouponException 이 난다") {
			shouldThrow<InvalidCouponException> { create(totalQuantity = 0) }
		}

		test("유효일수가 0 이면 발급 즉시 만료되므로 InvalidCouponException 이 난다") {
			shouldThrow<InvalidCouponException> { create(validityDays = 0) }
		}
	}
})
