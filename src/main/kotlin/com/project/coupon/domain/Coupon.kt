package com.project.coupon.domain

import com.project.coupon.support.InvalidCouponException
import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(name = "coupon")
class Coupon(
	@Column(nullable = false, length = Coupon.NAME_MAX_LENGTH)
	var name: String,

	@Column(name = "total_quantity", nullable = false)
	var totalQuantity: Int,

	@Column(name = "issued_quantity", nullable = false)
	var issuedQuantity: Int = 0,

	@Column(name = "validity_days", nullable = false)
	var validityDays: Int,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: LocalDateTime,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,
) {
	init {
		if (name.isBlank() || name.length > NAME_MAX_LENGTH) {
			throw InvalidCouponException("행사 이름은 1~${NAME_MAX_LENGTH}자여야 합니다")
		}
		if (totalQuantity <= 0) {
			throw InvalidCouponException("총수량은 1 이상이어야 합니다")
		}
		if (validityDays <= 0) {
			throw InvalidCouponException("유효일수는 1 이상이어야 합니다")
		}
	}

	fun isSoldOut(): Boolean = issuedQuantity >= totalQuantity

	companion object {
		const val NAME_MAX_LENGTH = 80
		const val FIXED_TOTAL_QUANTITY = 10_000
		const val FIXED_VALIDITY_DAYS = 7

		fun create(name: String, createdAt: LocalDateTime): Coupon = Coupon(
			name = name,
			totalQuantity = FIXED_TOTAL_QUANTITY,
			validityDays = FIXED_VALIDITY_DAYS,
			createdAt = createdAt,
		)
	}
}
