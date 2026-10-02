package com.project.coupon.api.dto

import com.project.coupon.domain.Coupon
import java.time.LocalDateTime

class CouponResponse(
	val id: Long,
	val name: String,
	val totalQuantity: Int,
	val issuedQuantity: Int,
	val validityDays: Int,
	val createdAt: LocalDateTime,
) {
	companion object {
		fun from(coupon: Coupon): CouponResponse = CouponResponse(
			id = requireNotNull(coupon.id),
			name = coupon.name,
			totalQuantity = coupon.totalQuantity,
			issuedQuantity = coupon.issuedQuantity,
			validityDays = coupon.validityDays,
			createdAt = coupon.createdAt,
		)
	}
}
