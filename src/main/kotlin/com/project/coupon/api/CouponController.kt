package com.project.coupon.api

import com.project.coupon.api.dto.CouponResponse
import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.api.dto.IssuanceAcceptedResponse
import com.project.coupon.application.CouponService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/coupons")
class CouponController(
	private val couponService: CouponService,
) {

	@PostMapping
	fun create(@RequestBody request: CreateCouponRequest): ResponseEntity<CouponResponse> {
		val coupon = couponService.createCoupon(request)
		return ResponseEntity.status(HttpStatus.CREATED).body(CouponResponse.from(coupon))
	}

	@PostMapping("/{couponId}/issue")
	fun issue(
		@PathVariable couponId: Long,
		@RequestHeader("X-User-Id") userId: Long,
	): ResponseEntity<IssuanceAcceptedResponse> {
		couponService.issue(couponId, userId)
		return ResponseEntity.status(HttpStatus.ACCEPTED).body(IssuanceAcceptedResponse(couponId = couponId, userId = userId))
	}
}
