package com.project.coupon.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IssuanceRepository : JpaRepository<Issuance, Long> {
	fun existsByUserIdAndCouponId(userId: Long, couponId: Long): Boolean

	fun findByUserIdAndCouponId(userId: Long, couponId: Long): Issuance?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select i from Issuance i where i.id = :id")
	fun findByIdForUpdate(@Param("id") id: Long): Issuance?

	@Query("select i.userId from Issuance i where i.couponId = :couponId")
	fun findUserIdsByCouponId(@Param("couponId") couponId: Long): List<Long>
}
