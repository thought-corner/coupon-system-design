package com.project.coupon.api.dto

class CreateCouponRequest(
	val name: String,
	val totalQuantity: Int = 10_000,
	val validityDays: Int = 7,
)
