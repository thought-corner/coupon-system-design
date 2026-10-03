package com.project.coupon.application

import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.InvalidCouponException
import com.project.coupon.support.IssuanceBusyException
import com.project.coupon.support.SoldOutException
import org.slf4j.LoggerFactory
import org.springframework.context.ApplicationEventPublisher
import org.springframework.core.task.TaskRejectedException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class CouponService(
	private val couponRepository: CouponRepository,
	private val issuanceGate: IssuanceGate,
	private val gateSeedReader: GateSeedReader,
	private val eventPublisher: ApplicationEventPublisher,
	private val clock: Clock,
) {

	@Transactional
	fun createCoupon(request: CreateCouponRequest): Coupon {
		if (request.totalQuantity != null || request.validityDays != null) {
			throw InvalidCouponException("총수량·유효일수는 ${Coupon.FIXED_TOTAL_QUANTITY}매·${Coupon.FIXED_VALIDITY_DAYS}일로 고정이라 지정할 수 없습니다")
		}
		return couponRepository.save(Coupon.create(request.name, LocalDateTime.now(clock)))
	}

	fun issue(couponId: Long, userId: Long) {
		passGate(couponId, userId)
		try {
			eventPublisher.publishEvent(IssuanceRequested(couponId, userId))
		} catch (e: Exception) {
			runCatching { issuanceGate.release(couponId, userId) }.onFailure {
				e.addSuppressed(it)
				log.error("선점 해제 실패 — Redis 재고 누수 couponId={} userId={}", couponId, userId, it)
			}
			if (e is TaskRejectedException) {
				throw IssuanceBusyException()
			}
			throw e
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

	companion object {
		private val log = LoggerFactory.getLogger(CouponService::class.java)
	}
}
