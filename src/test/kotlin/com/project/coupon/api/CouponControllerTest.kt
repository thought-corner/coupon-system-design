package com.project.coupon.api

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc
import org.springframework.context.annotation.Import
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.MockMvc
import org.springframework.test.web.servlet.post

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class CouponControllerTest(
	private val mockMvc: MockMvc,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest {
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	test("이름만 주고 행사를 만들면 10,000매·7일·발급 수량 0 으로 저장되고 201 로 반환된다") {
		val result = mockMvc.post("/api/coupons") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":"가을 할인"}"""
		}.andExpect {
			status { isCreated() }
			jsonPath("$.name") { value("가을 할인") }
			jsonPath("$.totalQuantity") { value(10_000) }
			jsonPath("$.validityDays") { value(7) }
			jsonPath("$.issuedQuantity") { value(0) }
			jsonPath("$.createdAt") { value("2026-10-03T10:00:00") }
		}.andReturn()

		val id = Regex("\"id\":(\\d+)").find(result.response.contentAsString).shouldNotBeNull().groupValues[1].toLong()
		val saved = couponRepository.findById(id).get()
		saved.totalQuantity shouldBe 10_000
		saved.validityDays shouldBe 7
		saved.issuedQuantity shouldBe 0
		saved.createdAt shouldBe FixedClockConfiguration.NOW
	}

	context("총수량·유효일수는 10,000매·7일 고정이라 요청에 담으면 400 INVALID_COUPON 으로 거절되고 저장되지 않는다") {
		listOf(
			"총수량" to """{"name":"가을 할인","totalQuantity":10000}""",
			"유효일수" to """{"name":"가을 할인","validityDays":7}""",
		).forEach { (field, body) ->
			test("$field 를 고정값과 같은 값으로 보내도 거절된다") {
				mockMvc.post("/api/coupons") {
					contentType = MediaType.APPLICATION_JSON
					content = body
				}.andExpect {
					status { isBadRequest() }
					jsonPath("$.code") { value("INVALID_COUPON") }
				}

				couponRepository.count() shouldBe 0
			}
		}
	}

	test("이름이 공백뿐이면 400 INVALID_COUPON 으로 거절되고 저장되지 않는다") {
		mockMvc.post("/api/coupons") {
			contentType = MediaType.APPLICATION_JSON
			content = """{"name":" "}"""
		}.andExpect {
			status { isBadRequest() }
			jsonPath("$.code") { value("INVALID_COUPON") }
		}

		couponRepository.count() shouldBe 0
	}

	context("발급") {
		fun createCoupon(totalQuantity: Int = 10_000, validityDays: Int = 7): Long =
			couponRepository.save(
				Coupon(
					name = "가을 할인",
					totalQuantity = totalQuantity,
					validityDays = validityDays,
					createdAt = FixedClockConfiguration.NOW,
				)
			).id.shouldNotBeNull()

		fun issue(couponId: Long, userId: Long) =
			mockMvc.post("/api/coupons/$couponId/issue") { header("X-User-Id", userId) }

		test("발급에 성공하면 ISSUED·발급 시각·만료 시각(발급 시각 + 유효일수)을 가진 발급 내역이 반환되고 발급 수량이 1 증가한다") {
			val couponId = createCoupon(validityDays = 3)

			issue(couponId, userId = 42).andExpect {
				status { isOk() }
				jsonPath("$.userId") { value(42) }
				jsonPath("$.couponId") { value(couponId) }
				jsonPath("$.status") { value("ISSUED") }
				jsonPath("$.issuedAt") { value("2026-10-03T10:00:00") }
				jsonPath("$.expiresAt") { value("2026-10-06T10:00:00") }
				jsonPath("$.usedAt") { value(null) }
			}

			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1
			issuanceRepository.existsByUserIdAndCouponId(42, couponId) shouldBe true
		}

		test("존재하지 않는 행사에 발급을 요청하면 404 COUPON_NOT_FOUND 로 거절된다") {
			issue(couponId = Long.MAX_VALUE, userId = 42).andExpect {
				status { isNotFound() }
				jsonPath("$.code") { value("COUPON_NOT_FOUND") }
			}
		}

		test("발급 수량이 총수량에 도달한 행사에 발급을 요청하면 409 SOLD_OUT 으로 거절되고 발급 수량은 그대로다") {
			val couponId = createCoupon(totalQuantity = 1)
			issue(couponId, userId = 1).andExpect { status { isOk() } }

			issue(couponId, userId = 2).andExpect {
				status { isConflict() }
				jsonPath("$.code") { value("SOLD_OUT") }
			}

			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1
			issuanceRepository.existsByUserIdAndCouponId(2, couponId) shouldBe false
		}

		test("이미 발급받은 사용자가 같은 행사에 다시 요청하면 409 ALREADY_ISSUED 로 거절되고 발급 수량은 그대로다") {
			val couponId = createCoupon()
			issue(couponId, userId = 42).andExpect { status { isOk() } }

			issue(couponId, userId = 42).andExpect {
				status { isConflict() }
				jsonPath("$.code") { value("ALREADY_ISSUED") }
			}

			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1
		}

		test("이미 발급받은 사용자가 매진 뒤에 다시 요청하면 SOLD_OUT 이 아니라 409 ALREADY_ISSUED 로 거절된다") {
			val couponId = createCoupon(totalQuantity = 1)
			issue(couponId, userId = 42).andExpect { status { isOk() } }

			issue(couponId, userId = 42).andExpect {
				status { isConflict() }
				jsonPath("$.code") { value("ALREADY_ISSUED") }
			}
		}
	}
})
