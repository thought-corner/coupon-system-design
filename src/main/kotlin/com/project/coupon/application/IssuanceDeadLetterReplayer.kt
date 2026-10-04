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
import org.springframework.transaction.PlatformTransactionManager
import org.springframework.transaction.TransactionDefinition
import org.springframework.transaction.support.TransactionTemplate
import java.time.Clock
import java.time.Duration
import java.time.LocalDateTime

@Component
class IssuanceDeadLetterReplayer(
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val issuanceRepository: IssuanceRepository,
	private val issuanceRequestPublisher: IssuanceRequestPublisher,
	private val clock: Clock,
	transactionManager: PlatformTransactionManager,
	@param:Value("\${coupon.dead-letter.replay-delay}") private val replayDelay: Duration,
) {

	private val transactionTemplate = TransactionTemplate(transactionManager).apply {
		isolationLevel = TransactionDefinition.ISOLATION_READ_COMMITTED
	}

	private var lastFailureLoggedAt: Long? = null

	@Scheduled(fixedDelay = REPLAY_INTERVAL_MILLIS)
	fun replayDue() {
		val claims = claimDue()
		claims.forEachIndexed { index, claim ->
			if (!replay(claim)) {
				returnToPending(claims.drop(index))
				return
			}
		}
	}

	private fun claimDue(): List<ReplayClaim> =
		transactionTemplate.execute {
			val now = LocalDateTime.now(clock)
			deadLetterRepository.lockDueForReplay(now.minus(replayDelay), now.minus(STALE_REPLAYING_AFTER), BATCH_SIZE)
				.mapNotNull { claim(it, now) }
		}.orEmpty()

	private fun claim(deadLetter: IssuanceDeadLetter, now: LocalDateTime): ReplayClaim? {
		val event = deadLetter.toEvent()
		val newAttempt = deadLetter.status == DeadLetterStatus.PENDING_REPLAY
		deadLetter.updatedAt = now
		deadLetter.status = when {
			isAlreadyApplied(event) -> DeadLetterStatus.RESOLVED
			deadLetter.replayFailures >= IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS -> DeadLetterStatus.ALERTED
			deadLetter.createdAt.isBefore(now.minus(REPLAY_WINDOW)) -> DeadLetterStatus.ALERTED
			else -> DeadLetterStatus.REPLAYING
		}
		if (deadLetter.status != DeadLetterStatus.REPLAYING) {
			return null
		}
		if (newAttempt) {
			deadLetter.replayAttempts++
		}
		return ReplayClaim(checkNotNull(deadLetter.id), deadLetter.replayAttempts, event)
	}

	private fun isAlreadyApplied(event: IssuanceRequested): Boolean =
		issuanceRepository.findByUserIdAndCouponId(event.userId, event.couponId)?.messageId == event.messageId

	private fun replay(claim: ReplayClaim): Boolean =
		try {
			issuanceRequestPublisher.publishReplay(claim.event, claim.deadLetterId, claim.attempt)
			true
		} catch (e: Exception) {
			if (e is InterruptedException) {
				Thread.currentThread().interrupt()
			}
			logReplayFailure(claim, e)
			false
		}

	private fun returnToPending(unsent: List<ReplayClaim>) {
		runCatching {
			transactionTemplate.executeWithoutResult {
				deadLetterRepository.returnToPendingReplay(unsent.map { it.deadLetterId }, LocalDateTime.now(clock))
			}
		}.onFailure {
			log.error("DLT 재처리 되돌리기 실패 — 재처리 중으로 남아 회수를 기다림 deadLetterIds={}", unsent.map { claim -> claim.deadLetterId }, it)
		}
	}

	private fun logReplayFailure(claim: ReplayClaim, cause: Exception) {
		val now = clock.millis()
		val last = lastFailureLoggedAt
		if (last == null || now - last >= FAILURE_LOG_INTERVAL_MILLIS) {
			lastFailureLoggedAt = now
			log.warn("DLT 재처리 발행 실패 — 다음 주기에 다시 시도 deadLetterId={} messageId={}", claim.deadLetterId, claim.event.messageId, cause)
		}
	}

	private fun IssuanceDeadLetter.toEvent() =
		IssuanceRequested(checkNotNull(messageId), checkNotNull(couponId), checkNotNull(userId))

	private class ReplayClaim(val deadLetterId: Long, val attempt: Int, val event: IssuanceRequested)

	companion object {
		private val log = LoggerFactory.getLogger(IssuanceDeadLetterReplayer::class.java)
		private const val REPLAY_INTERVAL_MILLIS = 1_000L
		private const val BATCH_SIZE = 100
		private const val FAILURE_LOG_INTERVAL_MILLIS = 60_000L
		private val STALE_REPLAYING_AFTER: Duration = Duration.ofMinutes(10)
		val REPLAY_WINDOW: Duration = Duration.ofMillis(KafkaConfig.ISSUANCE_RETENTION_MILLIS)
	}
}
