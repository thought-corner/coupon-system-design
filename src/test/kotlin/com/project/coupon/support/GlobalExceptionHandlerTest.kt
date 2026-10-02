package com.project.coupon.support

import io.kotest.core.spec.style.FunSpec
import org.springframework.boot.test.context.TestComponent
import org.springframework.http.MediaType
import org.springframework.test.web.servlet.get
import org.springframework.test.web.servlet.setup.MockMvcBuilders
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

class GlobalExceptionHandlerTest : FunSpec({

	val mockMvc = MockMvcBuilders
		.standaloneSetup(ThrowingController())
		.setControllerAdvice(GlobalExceptionHandler())
		.build()

	test("도메인 예외는 그 예외의 상태 코드와 code 를 담은 ProblemDetail 로 응답된다") {
		mockMvc.get("/test/sold-out").andExpect {
			status { isConflict() }
			content { contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON) }
			jsonPath("$.status") { value(409) }
			jsonPath("$.code") { value("SOLD_OUT") }
			jsonPath("$.title") { value("Sold Out") }
			jsonPath("$.detail") { value("쿠폰이 매진되었습니다") }
			jsonPath("$.instance") { value("/test/sold-out") }
		}
	}

	test("404 계열 도메인 예외는 404 로 응답된다") {
		mockMvc.get("/test/not-found").andExpect {
			status { isNotFound() }
			jsonPath("$.code") { value("COUPON_NOT_FOUND") }
		}
	}
})

// standaloneSetup 도 @Controller 가 있어야 매핑을 등록한다(없으면 404 — 실측).
// @TestComponent 는 @SpringBootTest 의 컴포넌트 스캔에서 이 클래스를 빼 준다 — 없으면 모든 통합 테스트 컨텍스트에 /test/* 매핑이 섞인다.
@TestComponent
@RestController
private class ThrowingController {

	@GetMapping("/test/sold-out")
	fun soldOut(): Nothing = throw SoldOutException()

	@GetMapping("/test/not-found")
	fun notFound(): Nothing = throw CouponNotFoundException()
}
