package com.project.coupon.application

import com.project.coupon.support.KafkaConfig
import org.apache.kafka.clients.producer.ProducerRecord
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.stereotype.Component
import java.util.concurrent.TimeUnit

@Component
class IssuanceRequestPublisher(
	private val kafkaTemplate: KafkaTemplate<String, IssuanceRequested>,
) {

	fun publish(event: IssuanceRequested) {
		send(record(event))
	}

	fun publishReplay(event: IssuanceRequested, deadLetterId: Long) {
		send(record(event).apply { headers().add(KafkaConfig.REPLAY_HEADER, deadLetterId.toString().toByteArray()) })
	}

	private fun record(event: IssuanceRequested) =
		ProducerRecord(KafkaConfig.ISSUANCE_REQUESTED_TOPIC, event.couponId.toString(), event)

	private fun send(record: ProducerRecord<String, IssuanceRequested>) {
		kafkaTemplate.send(record).get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS)
	}

	companion object {
		private const val SEND_TIMEOUT_SECONDS = 10L
	}
}
