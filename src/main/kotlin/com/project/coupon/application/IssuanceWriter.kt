package com.project.coupon.application

import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.domain.getCoupon
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.SoldOutException
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Component
class IssuanceWriter(
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
	private val clock: Clock,
) {

	@Transactional
	fun write(couponId: Long, userId: Long): Issuance {
		val coupon = couponRepository.getCoupon(couponId)
		val issuance = insertIssuance(userId, couponId, coupon.validityDays)
		if (couponRepository.increaseIssuedQuantityIfAvailable(couponId) == 0) {
			throw SoldOutException()
		}
		return issuance
	}

	private fun insertIssuance(userId: Long, couponId: Long, validityDays: Int): Issuance {
		val now = LocalDateTime.now(clock)
		return try {
			issuanceRepository.save(
				Issuance(
					userId = userId,
					couponId = couponId,
					issuedAt = now,
					expiresAt = now.plusDays(validityDays.toLong()),
				)
			)
		} catch (e: DataIntegrityViolationException) {
			throw AlreadyIssuedException()
		}
	}
}
