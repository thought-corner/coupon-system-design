package com.project.coupon.domain

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
	@Column(nullable = false, length = 80)
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
	fun isSoldOut(): Boolean = issuedQuantity >= totalQuantity
}
