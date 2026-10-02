package com.project.coupon.domain

import com.project.coupon.support.AlreadyUsedException
import com.project.coupon.support.ExpiredException
import com.project.coupon.support.NotOwnerException
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import java.time.LocalDateTime

class IssuanceTest : FunSpec({

	val expiresAt = LocalDateTime.of(2026, 10, 10, 10, 0)
	val beforeExpiry = expiresAt.minusDays(1)

	fun issuance() = Issuance(
		userId = 42,
		couponId = 1,
		issuedAt = expiresAt.minusDays(7),
		expiresAt = expiresAt,
	)

	test("만료 시각 1초 전에는 만료가 아니다") {
		issuance().isExpired(expiresAt.minusSeconds(1)) shouldBe false
	}

	test("만료 시각 정각에는 만료다") {
		issuance().isExpired(expiresAt) shouldBe true
	}

	test("주인이 만료 전에 사용하면 상태가 USED 가 되고 사용 시각이 기록된다") {
		val issuance = issuance()

		issuance.use(userId = 42, now = beforeExpiry)

		issuance.status shouldBe IssuanceStatus.USED
		issuance.usedAt shouldBe beforeExpiry
	}

	test("다른 사용자가 사용하면 NotOwnerException 이 나고 상태는 그대로다") {
		val issuance = issuance()

		shouldThrow<NotOwnerException> { issuance.use(userId = 7, now = beforeExpiry) }

		issuance.status shouldBe IssuanceStatus.ISSUED
	}

	test("이미 사용한 쿠폰을 다시 사용하면 AlreadyUsedException 이 나고 처음 사용 시각은 그대로다") {
		val issuance = issuance()
		issuance.use(userId = 42, now = beforeExpiry)

		shouldThrow<AlreadyUsedException> { issuance.use(userId = 42, now = beforeExpiry.plusHours(1)) }

		issuance.usedAt shouldBe beforeExpiry
	}

	test("만료 시각 정각에 사용하면 ExpiredException 이 나고 상태는 ISSUED 그대로다") {
		val issuance = issuance()

		shouldThrow<ExpiredException> { issuance.use(userId = 42, now = expiresAt) }

		issuance.status shouldBe IssuanceStatus.ISSUED
		issuance.usedAt shouldBe null
	}

	test("다른 사용자의 쿠폰은 이미 사용됐어도 NotOwnerException 이 먼저 난다") {
		val issuance = issuance()
		issuance.use(userId = 42, now = beforeExpiry)

		shouldThrow<NotOwnerException> { issuance.use(userId = 7, now = beforeExpiry) }
	}
})
