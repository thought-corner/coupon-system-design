package com.project.coupon.domain

import org.springframework.data.jpa.repository.JpaRepository

interface IssuanceRepository : JpaRepository<Issuance, Long> {
	fun existsByUserIdAndCouponId(userId: Long, couponId: Long): Boolean
}
