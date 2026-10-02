package com.project.coupon.application

import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.CouponNotFoundException
import com.project.coupon.support.SoldOutException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class CouponService(
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val clock: Clock,
) {

	@Transactional
	fun createCoupon(request: CreateCouponRequest): Coupon {
		val coupon = Coupon(
			name = request.name,
			totalQuantity = request.totalQuantity,
			validityDays = request.validityDays,
			createdAt = LocalDateTime.now(clock),
		)
		return couponRepository.save(coupon)
	}

	@Transactional
	fun issue(couponId: Long, userId: Long): Issuance {
		val coupon = couponRepository.findById(couponId)
			.orElseThrow { CouponNotFoundException() }

		if (coupon.isSoldOut()) {
			throw SoldOutException()
		}
		if (issuanceRepository.existsByUserIdAndCouponId(userId, couponId)) {
			throw AlreadyIssuedException()
		}

		coupon.issuedQuantity++

		val now = LocalDateTime.now(clock)
		return issuanceRepository.save(
			Issuance(
				userId = userId,
				couponId = couponId,
				issuedAt = now,
				expiresAt = now.plusDays(coupon.validityDays.toLong()),
			)
		)
	}
}
