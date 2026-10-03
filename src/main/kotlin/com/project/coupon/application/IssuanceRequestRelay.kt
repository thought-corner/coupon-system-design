package com.project.coupon.application

import com.project.coupon.domain.PendingIssuanceRequestRepository
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional

@Component
class IssuanceRequestRelay(
	private val pendingIssuanceRequestRepository: PendingIssuanceRequestRepository,
	private val issuanceRequestPublisher: IssuanceRequestPublisher,
) {

	@Scheduled(fixedDelay = RELAY_INTERVAL_MILLIS)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	fun relay() {
		for (pending in pendingIssuanceRequestRepository.lockOldest(BATCH_SIZE)) {
			try {
				issuanceRequestPublisher.publish(IssuanceRequested(pending.messageId, pending.couponId, pending.userId))
				pendingIssuanceRequestRepository.delete(pending)
			} catch (e: Exception) {
				if (e is InterruptedException) {
					Thread.currentThread().interrupt()
				}
				pending.attempts++
				if (pending.attempts == ALERT_ATTEMPTS || pending.attempts % REPEATED_ALERT_ATTEMPTS == 0) {
					log.error("발급 요청 재발행 반복 실패 attempts={} messageId={}", pending.attempts, pending.messageId, e)
				}
				return
			}
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(IssuanceRequestRelay::class.java)
		private const val RELAY_INTERVAL_MILLIS = 1_000L
		private const val BATCH_SIZE = 100
		private const val ALERT_ATTEMPTS = 10
		private const val REPEATED_ALERT_ATTEMPTS = 60
	}
}
