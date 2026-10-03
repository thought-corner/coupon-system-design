package com.project.coupon.application

import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.AlreadyIssuedException
import com.project.coupon.support.DomainException
import com.project.coupon.support.KafkaConfig
import com.project.coupon.support.SoldOutException
import org.slf4j.LoggerFactory
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.messaging.handler.annotation.Header
import org.springframework.stereotype.Component

@Component
class IssuanceRequestedConsumer(
	private val issuanceWriter: IssuanceWriter,
	private val issuanceRepository: IssuanceRepository,
	private val issuanceGate: IssuanceGate,
	private val deadLetterRecorder: IssuanceDeadLetterRecorder,
) {

	@KafkaListener(
		topics = [KafkaConfig.ISSUANCE_REQUESTED_TOPIC],
		groupId = KafkaConfig.ISSUANCE_WRITER_GROUP,
		concurrency = KafkaConfig.ISSUANCE_WRITER_CONCURRENCY,
	)
	fun consume(
		event: IssuanceRequested,
		@Header(name = KafkaConfig.REPLAY_HEADER, required = false) replayOf: ByteArray?,
	) {
		handle(event)
		if (replayOf != null) {
			resolveReplay(event)
		}
	}

	private fun handle(event: IssuanceRequested) {
		try {
			issuanceWriter.write(event)
		} catch (e: AlreadyIssuedException) {
			if (isAlreadyApplied(event)) {
				log.debug("이미 반영된 발급 요청 — 재전달로 보고 무시 messageId={}", event.messageId)
				return
			}
			compensate(event, e) { issuanceGate.restoreStock(event.couponId, event.messageId) }
		} catch (e: SoldOutException) {
			compensate(event, e) { issuanceGate.markSoldOut(event.couponId, event.userId) }
		} catch (e: DomainException) {
			compensate(event, e) { issuanceGate.release(event.couponId, event.userId) }
		}
	}

	private fun resolveReplay(event: IssuanceRequested) {
		runCatching { deadLetterRecorder.resolveReplay(event.messageId) }.onFailure {
			log.error("재처리 결과 기록 실패 — DLT 기록이 재처리 중으로 남음 messageId={}", event.messageId, it)
		}
	}

	private fun isAlreadyApplied(event: IssuanceRequested): Boolean =
		issuanceRepository.findByUserIdAndCouponId(event.userId, event.couponId)?.messageId == event.messageId

	private fun compensate(event: IssuanceRequested, cause: DomainException, action: () -> Unit) {
		log.warn("문지기 통과 뒤 DB 가 거절 code={} messageId={} couponId={} userId={}", cause.code, event.messageId, event.couponId, event.userId)
		runCatching(action).onFailure {
			log.error("문지기 보상 실패 — Redis·DB 불일치 messageId={} couponId={} userId={}", event.messageId, event.couponId, event.userId, it)
		}
	}

	companion object {
		private val log = LoggerFactory.getLogger(IssuanceRequestedConsumer::class.java)
	}
}
