package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.domain.IssuanceStatus
import com.project.coupon.runConcurrently
import com.project.coupon.support.AlreadyUsedException
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class IssuanceUseConcurrencyTest(
	private val issuanceService: IssuanceService,
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest {
		issuanceRepository.deleteAll()
	}

	// 사용 판정만 보려는 것이라 발급 경로를 거치지 않고 저장한다
	fun saveIssuance(userId: Long): Long = issuanceRepository.save(
		Issuance(
			userId = userId,
			couponId = 1,
			issuedAt = FixedClockConfiguration.NOW,
			expiresAt = FixedClockConfiguration.NOW.plusDays(7),
		)
	).id.shouldNotBeNull()

	// 같은 사용자가 버튼을 여러 번 누르거나 여러 기기에서 동시에 쓰는 상황
	test("같은 발급 쿠폰에 사용 요청이 동시에 몰려도 성공은 1번이고 나머지는 이미 사용됨으로 거절된다") {
		val issuanceId = saveIssuance(userId = 42)
		val requests = 20

		val results = runConcurrently((1..requests).toList()) { issuanceService.use(issuanceId, userId = 42) }

		assertSoftly {
			results.count { it.isSuccess } shouldBe 1
			results.count { it.exceptionOrNull() is AlreadyUsedException } shouldBe requests - 1
			issuanceRepository.findById(issuanceId).get().status shouldBe IssuanceStatus.USED
		}
	}
})
