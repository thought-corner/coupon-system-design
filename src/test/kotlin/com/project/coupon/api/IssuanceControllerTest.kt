package com.project.coupon.api

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.domain.IssuanceStatus
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post
import java.time.LocalDateTime

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class IssuanceControllerTest(
	private val mockMvc: MockMvc,
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest {
		issuanceRepository.deleteAll()
	}

	// 발급 경로를 거치지 않고 저장한다 — 사용 판정만 보려는 것이라 쿠폰 행사 행은 필요 없다
	fun saveIssuance(
		userId: Long = 42,
		expiresAt: LocalDateTime = FixedClockConfiguration.NOW.plusDays(7),
	): Long = issuanceRepository.save(
		Issuance(
			userId = userId,
			couponId = 1,
			issuedAt = expiresAt.minusDays(7),
			expiresAt = expiresAt,
		)
	).id.shouldNotBeNull()

	fun use(issuanceId: Long, userId: Long) =
		mockMvc.post("/api/issuances/$issuanceId/use") { header("X-User-Id", userId) }

	test("사용에 성공하면 상태가 USED 가 되고 사용 시각이 기록된다") {
		val issuanceId = saveIssuance()

		use(issuanceId, userId = 42).andExpect {
			status { isOk() }
			jsonPath("$.id") { value(issuanceId) }
			jsonPath("$.status") { value("USED") }
			jsonPath("$.usedAt") { value("2026-10-03T10:00:00") }
		}

		val saved = issuanceRepository.findById(issuanceId).get()
		saved.status shouldBe IssuanceStatus.USED
		saved.usedAt shouldBe FixedClockConfiguration.NOW
	}

	test("존재하지 않는 발급 내역의 사용을 요청하면 404 ISSUANCE_NOT_FOUND 로 거절된다") {
		use(issuanceId = Long.MAX_VALUE, userId = 42).andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("ISSUANCE_NOT_FOUND") }
		}
	}

	test("다른 사용자의 발급 내역 사용을 요청하면 403 NOT_OWNER 로 거절되고 상태는 그대로다") {
		val issuanceId = saveIssuance(userId = 42)

		use(issuanceId, userId = 7).andExpect {
			status { isForbidden() }
			jsonPath("$.code") { value("NOT_OWNER") }
		}

		issuanceRepository.findById(issuanceId).get().status shouldBe IssuanceStatus.ISSUED
	}

	test("이미 사용한 발급 내역의 사용을 다시 요청하면 409 ALREADY_USED 로 거절된다") {
		val issuanceId = saveIssuance()
		use(issuanceId, userId = 42).andExpect { status { isOk() } }

		use(issuanceId, userId = 42).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("ALREADY_USED") }
		}
	}

	test("만료 시각 정각에 사용을 요청하면 409 EXPIRED 로 거절되고 상태는 ISSUED 그대로다") {
		val issuanceId = saveIssuance(expiresAt = FixedClockConfiguration.NOW)

		use(issuanceId, userId = 42).andExpect {
			status { isConflict() }
			jsonPath("$.code") { value("EXPIRED") }
		}

		val saved = issuanceRepository.findById(issuanceId).get()
		saved.status shouldBe IssuanceStatus.ISSUED
		saved.usedAt shouldBe null
	}
})
