package com.project.coupon.application

import com.project.coupon.domain.DeadLetterStatus
import com.project.coupon.domain.IssuanceDeadLetter
import com.project.coupon.domain.IssuanceDeadLetterRepository
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Isolation
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Component
class IssuanceDeadLetterAlertSender(
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val alerter: DeadLetterAlerter,
	private val clock: Clock,
) {

	@Scheduled(fixedDelay = ALERT_INTERVAL_MILLIS)
	@Transactional(isolation = Isolation.READ_COMMITTED)
	fun sendPending() {
		for (deadLetter in deadLetterRepository.lockUnalerted(BATCH_SIZE)) {
			if (!alerter.alert(describe(deadLetter))) {
				return
			}
			deadLetter.alertedAt = LocalDateTime.now(clock)
		}
	}

	companion object {
		private const val ALERT_INTERVAL_MILLIS = 10_000L
		private const val BATCH_SIZE = 20

		fun describe(deadLetter: IssuanceDeadLetter): String {
			val failedReplays = deadLetter.deadLetterCount - 1
			return when {
				deadLetter.status == DeadLetterStatus.UNREADABLE ->
					"[쿠폰 발급 DLT] 읽을 수 없는 메시지 — 재처리 불가, 확인 필요. " +
						"deadLetterId=${deadLetter.id} couponId=${deadLetter.couponId} 원인=${deadLetter.failureType}"
				failedReplays >= IssuanceDeadLetterRecorder.MAX_REPLAY_ATTEMPTS ->
					"[쿠폰 발급 DLT] 재처리 ${failedReplays}회 실패 — 관리자 수동 처리 필요. " + identity(deadLetter)
				else ->
					"[쿠폰 발급 DLT] 재처리 기한 초과 — 자동 재처리 중단, 관리자 수동 처리 필요. " + identity(deadLetter)
			}
		}

		private fun identity(deadLetter: IssuanceDeadLetter): String =
			"deadLetterId=${deadLetter.id} messageId=${deadLetter.messageId} couponId=${deadLetter.couponId} " +
				"userId=${deadLetter.userId} 원인=${deadLetter.failureType}"
	}
}
