package com.project.coupon.application

import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.Issuance
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.InvalidCouponException
import com.project.coupon.support.SoldOutException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class CouponService(
	private val couponRepository: CouponRepository,
	private val issuanceGate: IssuanceGate,
	private val gateSeedReader: GateSeedReader,
	private val issuanceWriter: IssuanceWriter,
	private val clock: Clock,
) {

	@Transactional
	fun createCoupon(request: CreateCouponRequest): Coupon {
		if (request.totalQuantity != null || request.validityDays != null) {
			throw InvalidCouponException("총수량·유효일수는 ${Coupon.FIXED_TOTAL_QUANTITY}매·${Coupon.FIXED_VALIDITY_DAYS}일로 고정이라 지정할 수 없습니다")
		}
		return couponRepository.save(Coupon.create(request.name, LocalDateTime.now(clock)))
	}

	fun issue(couponId: Long, userId: Long): Issuance {
		passGate(couponId, userId)
		return try {
			issuanceWriter.write(couponId, userId)
		} catch (e: Exception) {
			undoGatePass(couponId, userId, e)
		}
	}

	private fun passGate(couponId: Long, userId: Long) {
		var result = issuanceGate.tryPass(couponId, userId)
		if (result == GateResult.NOT_INITIALIZED) {
			initializeGate(couponId)
			result = issuanceGate.tryPass(couponId, userId)
		}

		when (result) {
			GateResult.PASSED -> Unit
			GateResult.SOLD_OUT -> throw SoldOutException()
			GateResult.DUPLICATE -> throw AlreadyIssuedException()
			GateResult.NOT_INITIALIZED -> error("문지기를 초기화한 직후에도 재고 키가 없습니다")
		}
	}

	private fun initializeGate(couponId: Long) {
		val seed = gateSeedReader.read(couponId)
		issuanceGate.initialize(couponId, seed.remaining, seed.issuedUserIds)
	}

	private fun undoGatePass(couponId: Long, userId: Long, cause: Exception): Nothing {
		runCatching {
			when (cause) {
				is AlreadyIssuedException -> issuanceGate.restoreStock(couponId)
				is SoldOutException -> issuanceGate.markSoldOut(couponId, userId)
				else -> issuanceGate.release(couponId, userId)
			}
		}.onFailure(cause::addSuppressed)
		throw cause
	}
}
