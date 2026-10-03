package com.project.coupon.application

import com.project.coupon.support.KafkaConfig
import org.apache.kafka.clients.consumer.ConsumerRecord
import org.springframework.kafka.annotation.KafkaListener
import org.springframework.kafka.listener.ListenerExecutionFailedException
import org.springframework.kafka.support.KafkaHeaders
import org.springframework.stereotype.Component

@Component
class IssuanceDeadLetterConsumer(
	private val recorder: IssuanceDeadLetterRecorder,
) {

	@KafkaListener(
		topics = [KafkaConfig.ISSUANCE_REQUESTED_DLT],
		groupId = KafkaConfig.ISSUANCE_DEAD_LETTER_GROUP,
		containerFactory = KafkaConfig.DEAD_LETTER_CONTAINER_FACTORY,
	)
	fun consume(record: ConsumerRecord<String?, ByteArray?>) {
		recorder.record(
			DeadLetterArrival(
				key = record.key(),
				payload = record.value(),
				partition = record.partition(),
				offset = record.offset(),
				exceptionClass = record.failureClass(),
				exceptionMessage = record.header(KafkaHeaders.DLT_EXCEPTION_MESSAGE),
			)
		)
	}

	private fun ConsumerRecord<String?, ByteArray?>.failureClass(): String? {
		val thrown = header(KafkaHeaders.DLT_EXCEPTION_FQCN)
		if (thrown != ListenerExecutionFailedException::class.java.name) {
			return thrown
		}
		return header(KafkaHeaders.DLT_EXCEPTION_CAUSE_FQCN) ?: thrown
	}

	private fun ConsumerRecord<String?, ByteArray?>.header(name: String): String? =
		headers().lastHeader(name)?.value()?.let { String(it) }
}
