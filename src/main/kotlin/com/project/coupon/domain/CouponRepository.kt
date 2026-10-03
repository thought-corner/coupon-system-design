package com.project.coupon.domain

import com.project.coupon.support.CouponNotFoundException
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface CouponRepository : JpaRepository<Coupon, Long> {

	@Modifying
	@Query(
		"update Coupon c set c.issuedQuantity = c.issuedQuantity + 1 " +
			"where c.id = :couponId and c.issuedQuantity < c.totalQuantity"
	)
	fun increaseIssuedQuantityIfAvailable(@Param("couponId") couponId: Long): Int
}

fun CouponRepository.getCoupon(couponId: Long): Coupon =
	findById(couponId).orElseThrow { CouponNotFoundException() }
