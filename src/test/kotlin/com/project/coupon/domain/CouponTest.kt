package com.project.coupon.domain

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
})
