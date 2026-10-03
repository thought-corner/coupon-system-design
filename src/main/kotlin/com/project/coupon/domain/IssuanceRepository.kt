package com.project.coupon.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IssuanceRepository : JpaRepository<Issuance, Long> {
	fun existsByUserIdAndCouponId(userId: Long, couponId: Long): Boolean

	@Query("select i.userId from Issuance i where i.couponId = :couponId")
	fun findUserIdsByCouponId(@Param("couponId") couponId: Long): List<Long>
}
