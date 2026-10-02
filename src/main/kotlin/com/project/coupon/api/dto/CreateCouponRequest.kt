package com.project.coupon.api.dto

class CreateCouponRequest(
	val name: String,
	val totalQuantity: Int? = null,
	val validityDays: Int? = null,
)
