package com.project.coupon.application

import com.project.coupon.api.dto.CreateCouponRequest
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.PendingIssuanceRequest
import com.project.coupon.domain.PendingIssuanceRequestRepository
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.InvalidCouponException
import com.project.coupon.support.IssuanceBusyException
import com.project.coupon.support.SoldOutException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

@Service
class CouponService(
	private val couponRepository: CouponRepository,
	private val issuanceGate: IssuanceGate,
	private val gateSeedReader: GateSeedReader,
	private val issuanceRequestPublisher: IssuanceRequestPublisher,
	private val pendingIssuanceRequestRepository: PendingIssuanceRequestRepository,
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
		val event = IssuanceRequested(UUID.randomUUID().toString(), couponId, userId)
		try {
			issuanceRequestPublisher.publish(event)
		} catch (e: Exception) {
			deferOrReject(event, e)
		}
	}

	private fun deferOrReject(event: IssuanceRequested, cause: Exception) {
		val wasInterrupted = cause is InterruptedException || Thread.interrupted()
		try {
			logPublishFailure(event.couponId, event.userId, cause)
			if (savePendingForRelay(event)) {
				return
			}
			runCatching { issuanceGate.release(event.couponId, event.userId) }.onFailure {
				log.error("선점 해제 실패 — Redis 재고 누수 couponId={} userId={}", event.couponId, event.userId, it)
			}
		} finally {
			if (wasInterrupted) {
				Thread.currentThread().interrupt()
			}
		}
		throw IssuanceBusyException()
	}

	private fun savePendingForRelay(event: IssuanceRequested): Boolean =
		runCatching {
			pendingIssuanceRequestRepository.save(
				PendingIssuanceRequest(
					messageId = event.messageId,
					couponId = event.couponId,
					userId = event.userId,
					createdAt = LocalDateTime.now(clock),
				)
			)
		}.onFailure {
			log.error("재발행 대기 저장 실패 — 선점 해제 후 거절 messageId={}", event.messageId, it)
		}.isSuccess

	private fun logPublishFailure(couponId: Long, userId: Long, cause: Exception) {
		val now = clock.millis()
		val last = lastPublishFailureLoggedAt.get()
		if (now - last >= PUBLISH_FAILURE_LOG_INTERVAL_MILLIS && lastPublishFailureLoggedAt.compareAndSet(last, now)) {
			log.warn(
				"발급 요청 발행 실패 — 재발행 대기로 저장, 직전 간격 동안 생략 {}건 couponId={} userId={}",
				suppressedPublishFailures.getAndSet(0), couponId, userId, cause,
			)
		} else {
			suppressedPublishFailures.incrementAndGet()
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

	private val lastPublishFailureLoggedAt = AtomicLong(0)
	private val suppressedPublishFailures = AtomicLong(0)

	companion object {
		private val log = LoggerFactory.getLogger(CouponService::class.java)
		private const val PUBLISH_FAILURE_LOG_INTERVAL_MILLIS = 60_000L
	}
}
