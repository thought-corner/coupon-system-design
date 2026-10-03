package com.project.coupon.application

import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.domain.getCoupon
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional

@Component
class GateSeedReader(
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
) {

	@Transactional(readOnly = true)
	fun read(couponId: Long): GateSeed {
		val coupon = couponRepository.getCoupon(couponId)
		return GateSeed(
			remaining = coupon.totalQuantity - coupon.issuedQuantity,
			issuedUserIds = issuanceRepository.findUserIdsByCouponId(couponId),
		)
	}
}

class GateSeed(
	val remaining: Int,
	val issuedUserIds: List<Long>,
)
