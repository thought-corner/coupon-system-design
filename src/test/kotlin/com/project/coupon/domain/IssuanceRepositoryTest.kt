package com.project.coupon.domain

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import io.kotest.assertions.throwables.shouldThrow
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.dao.DataIntegrityViolationException

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class IssuanceRepositoryTest(
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest { issuanceRepository.deleteAll() }

	fun issuance(userId: Long, couponId: Long) = Issuance(
		userId = userId,
		couponId = couponId,
		issuedAt = FixedClockConfiguration.NOW,
		expiresAt = FixedClockConfiguration.NOW.plusDays(7),
	)

	// 서비스의 사전 조회(existsBy…)를 동시 요청이 뚫어도 DB 가 두 번째 행을 거절하는지 — 1인 1매의 마지막 방어선
	test("같은 사용자·같은 행사의 발급 내역은 두 번째 저장이 유니크 제약으로 거절된다") {
		issuanceRepository.saveAndFlush(issuance(userId = 42, couponId = 1))

		shouldThrow<DataIntegrityViolationException> {
			issuanceRepository.saveAndFlush(issuance(userId = 42, couponId = 1))
		}
		issuanceRepository.count() shouldBe 1
	}

	test("같은 사용자라도 다른 행사면 저장된다") {
		issuanceRepository.saveAndFlush(issuance(userId = 42, couponId = 1))
		issuanceRepository.saveAndFlush(issuance(userId = 42, couponId = 2))

		issuanceRepository.count() shouldBe 2
	}
})
