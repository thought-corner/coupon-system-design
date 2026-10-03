package com.project.coupon.application

import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.CouponNotFoundException
import com.project.coupon.support.InvalidCouponException
import com.project.coupon.support.SoldOutException
import org.springframework.dao.DataIntegrityViolationException
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
		if (request.totalQuantity != null || request.validityDays != null) {
			throw InvalidCouponException("총수량·유효일수는 ${Coupon.FIXED_TOTAL_QUANTITY}매·${Coupon.FIXED_VALIDITY_DAYS}일로 고정이라 지정할 수 없습니다")
		}
		return couponRepository.save(Coupon.create(request.name, LocalDateTime.now(clock)))
	}

	@Transactional
	fun issue(couponId: Long, userId: Long): Issuance {
		val coupon = couponRepository.findByIdForUpdate(couponId)
			?: throw CouponNotFoundException()

		if (issuanceRepository.existsByUserIdAndCouponId(userId, couponId)) {
			throw AlreadyIssuedException()
		}
		if (coupon.isSoldOut()) {
			throw SoldOutException()
		}

		val now = LocalDateTime.now(clock)
		val issuance = try {
			issuanceRepository.save(
				Issuance(
					userId = userId,
					couponId = couponId,
					issuedAt = now,
					expiresAt = now.plusDays(coupon.validityDays.toLong()),
				)
			)
		} catch (e: DataIntegrityViolationException) {
			throw AlreadyIssuedException()
		}

		coupon.issuedQuantity++
		return issuance
	}
}
