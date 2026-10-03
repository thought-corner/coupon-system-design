package com.project.coupon.application

import com.project.coupon.domain.DeadLetterStatus
import com.project.coupon.domain.IssuanceDeadLetter
import com.project.coupon.domain.IssuanceDeadLetterRepository
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.KafkaConfig
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Component
class IssuanceDeadLetterReplayer(
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val issuanceRepository: IssuanceRepository,
	private val issuanceRequestPublisher: IssuanceRequestPublisher,
	private val clock: Clock,
	@param:Value("\${coupon.dead-letter.replay-delay}") private val replayDelay: Duration,
) {

	private var lastFailureLoggedAt: Long? = null

	@Scheduled(fixedDelay = REPLAY_INTERVAL_MILLIS)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	fun replayDue() {
		val now = LocalDateTime.now(clock)
		val due = deadLetterRepository.lockDueForReplay(now.minus(replayDelay), now.minus(STALE_REPLAYING_AFTER), BATCH_SIZE)
		for (deadLetter in due) {
			val event = deadLetter.toEvent()
			val next = when {
				isAlreadyApplied(event) -> DeadLetterStatus.RESOLVED
				deadLetter.createdAt.isBefore(now.minus(REPLAY_WINDOW)) -> DeadLetterStatus.ALERTED
				replay(deadLetter, event) -> DeadLetterStatus.REPLAYING
				else -> return
			}
			deadLetter.status = next
			deadLetter.updatedAt = now
		}
	}

	private fun isAlreadyApplied(event: IssuanceRequested): Boolean =
		issuanceRepository.findByUserIdAndCouponId(event.userId, event.couponId)?.messageId == event.messageId

	private fun replay(deadLetter: IssuanceDeadLetter, event: IssuanceRequested): Boolean =
		try {
			issuanceRequestPublisher.publishReplay(event, checkNotNull(deadLetter.id))
			true
		} catch (e: Exception) {
			if (e is InterruptedException) {
				Thread.currentThread().interrupt()
			}
			logReplayFailure(deadLetter, e)
			false
		}

	private fun logReplayFailure(deadLetter: IssuanceDeadLetter, cause: Exception) {
		val now = clock.millis()
		val last = lastFailureLoggedAt
		if (last == null || now - last >= FAILURE_LOG_INTERVAL_MILLIS) {
			lastFailureLoggedAt = now
			log.warn("DLT 재처리 발행 실패 — 다음 주기에 다시 시도 deadLetterId={} messageId={}", deadLetter.id, deadLetter.messageId, cause)
		}
	}

	private fun IssuanceDeadLetter.toEvent() =
		IssuanceRequested(checkNotNull(messageId), checkNotNull(couponId), checkNotNull(userId))

	companion object {
		private val log = LoggerFactory.getLogger(IssuanceDeadLetterReplayer::class.java)
		private const val REPLAY_INTERVAL_MILLIS = 1_000L
		private const val BATCH_SIZE = 100
		private const val FAILURE_LOG_INTERVAL_MILLIS = 60_000L
		private val STALE_REPLAYING_AFTER: Duration = Duration.ofMinutes(10)
		val REPLAY_WINDOW: Duration = Duration.ofMillis(KafkaConfig.ISSUANCE_RETENTION_MILLIS)
	}
}
