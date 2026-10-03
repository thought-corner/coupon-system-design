package com.project.coupon.application

import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.AsyncConfig
import com.project.coupon.support.DomainException
import com.project.coupon.support.SoldOutException
import org.slf4j.LoggerFactory
import org.springframework.context.event.EventListener
import org.springframework.scheduling.annotation.Async
import org.springframework.stereotype.Component

@Component
class IssuanceRequestedListener(
	private val issuanceWriter: IssuanceWriter,
	private val issuanceGate: IssuanceGate,
) {

	@Async(AsyncConfig.ISSUANCE_EXECUTOR)
	@EventListener
	fun handle(event: IssuanceRequested) {
		try {
			issuanceWriter.write(event.couponId, event.userId)
		} catch (e: Exception) {
			undoGatePass(event, e)
		}
	}

	private fun undoGatePass(event: IssuanceRequested, cause: Exception) {
		when (cause) {
			is DomainException ->
				log.warn("문지기 통과 뒤 DB 가 거절 code={} couponId={} userId={}", cause.code, event.couponId, event.userId)
			else ->
				log.warn("비동기 발급 반영 실패 couponId={} userId={}", event.couponId, event.userId, cause)
		}
		val wasInterrupted = Thread.interrupted()
		try {
			runCatching {
				when (cause) {
					is AlreadyIssuedException -> issuanceGate.restoreStock(event.couponId)
					is SoldOutException -> issuanceGate.markSoldOut(event.couponId, event.userId)
					else -> issuanceGate.release(event.couponId, event.userId)
				}
			}.onFailure {
				log.error("문지기 보상 실패 — Redis·DB 불일치 couponId={} userId={}", event.couponId, event.userId, it)
			}
		} finally {
			if (wasInterrupted) {
				Thread.currentThread().interrupt()
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(IssuanceRequestedListener::class.java)
	}
}
