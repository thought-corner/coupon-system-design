package com.project.coupon.application

import com.project.coupon.domain.DeadLetterStatus
import com.project.coupon.domain.IssuanceDeadLetter
import com.project.coupon.domain.IssuanceDeadLetterArrival
import com.project.coupon.domain.IssuanceDeadLetterArrivalRepository
import com.project.coupon.domain.IssuanceDeadLetterRepository
import org.springframework.stereotype.Component
import org.springframework.transaction.annotation.Transactional
import tools.jackson.databind.json.JsonMapper
import java.security.MessageDigest
import java.time.Clock
import java.time.LocalDateTime
import java.util.HexFormat

@Component
class IssuanceDeadLetterRecorder(
	private val deadLetterRepository: IssuanceDeadLetterRepository,
	private val arrivalRepository: IssuanceDeadLetterArrivalRepository,
	private val jsonMapper: JsonMapper,
	private val clock: Clock,
) {

	@Transactional
	fun record(arrival: DeadLetterArrival) {
		val payloadHash = arrival.payloadHash()
		if (arrivalRepository.existsByPartitionAndOffsetAndPayloadHash(arrival.partition, arrival.offset, payloadHash)) {
			return
		}
		val event = readableEvent(arrival.payload)
		val returned = event?.let { deadLetterRepository.findByMessageIdForUpdate(it.messageId) }
		val deadLetter = when {
			event == null -> saveNew(arrival, null, DeadLetterStatus.UNREADABLE)
			returned != null -> returned
			else -> saveNew(arrival, event, DeadLetterStatus.PENDING_REPLAY)
		}
		arrivalRepository.save(
			IssuanceDeadLetterArrival(
				deadLetterId = checkNotNull(deadLetter.id),
				partition = arrival.partition,
				offset = arrival.offset,
				payloadHash = payloadHash,
				replayAttempt = arrival.replayAttempt,
				arrivedAt = LocalDateTime.now(clock),
			)
		)
		returned?.let { countReturn(it, arrival) }
	}

	@Transactional
	fun resolveReplay(messageId: String) {
		deadLetterRepository.resolveReplaying(messageId, LocalDateTime.now(clock))
	}

	private fun readableEvent(payload: ByteArray?): IssuanceRequested? {
		if (payload == null) {
			return null
		}
		return runCatching { jsonMapper.readValue(payload, IssuanceRequested::class.java) }
			.getOrNull()
			?.takeIf { MESSAGE_ID_PATTERN.matches(it.messageId) }
	}

	private fun saveNew(arrival: DeadLetterArrival, event: IssuanceRequested?, status: DeadLetterStatus): IssuanceDeadLetter {
		val now = LocalDateTime.now(clock)
		return deadLetterRepository.save(
			IssuanceDeadLetter(
				messageId = event?.messageId,
				couponId = event?.couponId ?: arrival.key?.toLongOrNull(),
				userId = event?.userId,
				payload = arrival.payload ?: ByteArray(0),
				failureType = arrival.failureType(),
				failureReason = arrival.failureReason(),
				status = status,
				deadLetterCount = 1,
				createdAt = now,
				updatedAt = now,
			)
		)
	}

	private fun countReturn(deadLetter: IssuanceDeadLetter, arrival: DeadLetterArrival) {
		deadLetter.deadLetterCount++
		deadLetter.failureType = arrival.failureType()
		deadLetter.failureReason = arrival.failureReason()
		deadLetter.replayFailures = arrivalRepository.countFailedReplays(checkNotNull(deadLetter.id)).toInt()
		val next = nextStatus(deadLetter, arrival) ?: return
		deadLetter.status = next
		deadLetter.updatedAt = LocalDateTime.now(clock)
	}

	private fun nextStatus(deadLetter: IssuanceDeadLetter, arrival: DeadLetterArrival): DeadLetterStatus? {
		val reachedLimit = deadLetter.replayFailures >= MAX_REPLAY_ATTEMPTS
		val currentReplayFailed = deadLetter.status == DeadLetterStatus.REPLAYING &&
			arrival.replayAttempt == deadLetter.replayAttempts
		return when {
			deadLetter.status == DeadLetterStatus.PENDING_REPLAY && reachedLimit -> DeadLetterStatus.ALERTED
			currentReplayFailed && reachedLimit -> DeadLetterStatus.ALERTED
			currentReplayFailed -> DeadLetterStatus.PENDING_REPLAY
			else -> null
		}
	}

	companion object {
		const val MAX_REPLAY_ATTEMPTS = 3
		private val MESSAGE_ID_PATTERN = Regex("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
	}
}

class DeadLetterArrival(
	val key: String?,
	val payload: ByteArray?,
	val partition: Int,
	val offset: Long,
	val exceptionClass: String?,
	val exceptionMessage: String?,
	val replayAttempt: Int = 0,
) {
	fun payloadHash(): String =
		HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(payload ?: ByteArray(0)))

	fun failureType(): String = (exceptionClass ?: "알 수 없음").take(FAILURE_TYPE_MAX_LENGTH)

	fun failureReason(): String =
		"${failureType()}: ${exceptionMessage ?: ""}".take(IssuanceDeadLetter.FAILURE_REASON_MAX_LENGTH)

	companion object {
		private const val FAILURE_TYPE_MAX_LENGTH = 255
	}
}
