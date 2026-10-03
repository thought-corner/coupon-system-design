package com.project.coupon.api.dto

class IssuanceAcceptedResponse(
	val couponId: Long,
	val userId: Long,
) {
	val status: String = "PENDING"
}
