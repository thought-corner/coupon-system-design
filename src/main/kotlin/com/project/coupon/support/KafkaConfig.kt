package com.project.coupon.support

import com.project.coupon.application.DeadLetterAlerter
import org.apache.kafka.clients.admin.NewTopic
import org.apache.kafka.clients.consumer.ConsumerConfig
import org.apache.kafka.clients.producer.ProducerConfig
import org.apache.kafka.common.config.TopicConfig
import org.apache.kafka.common.serialization.ByteArrayDeserializer
import org.apache.kafka.common.serialization.ByteArraySerializer
import org.apache.kafka.common.serialization.StringDeserializer
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.kafka.config.ConcurrentKafkaListenerContainerFactory
import org.springframework.kafka.config.TopicBuilder
import org.springframework.kafka.core.ConsumerFactory
import org.springframework.kafka.core.DefaultKafkaConsumerFactory
import org.springframework.kafka.core.DefaultKafkaProducerFactory
import org.springframework.kafka.core.KafkaOperations
import org.springframework.kafka.core.KafkaTemplate
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer
import org.springframework.kafka.listener.DefaultErrorHandler
import org.springframework.util.backoff.ExponentialBackOff
import org.springframework.util.backoff.FixedBackOff

@Configuration
class KafkaConfig(
	@param:Value("\${coupon.kafka.issuance.replicas}") private val replicas: Int,
) {

	@Bean
	fun issuanceRequestedTopic(): NewTopic = issuanceTopic(ISSUANCE_REQUESTED_TOPIC)

	@Bean
	fun issuanceRequestedDeadLetterTopic(): NewTopic = issuanceTopic(ISSUANCE_REQUESTED_DLT)

	@Bean
	fun issuanceErrorHandler(
		kafkaTemplate: KafkaTemplate<Any, Any>,
		producerFactory: DefaultKafkaProducerFactory<Any, Any>,
	): DefaultErrorHandler {
		val rawBytesTemplate = KafkaTemplate(
			producerFactory.copyWithConfigurationOverride(
				mapOf(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG to ByteArraySerializer::class.java),
			),
		)
		val templatesByValueType = linkedMapOf<Class<*>, KafkaOperations<out Any, out Any>>(
			ByteArray::class.java to rawBytesTemplate,
			Any::class.java to kafkaTemplate,
		)
		return DefaultErrorHandler(
			DeadLetterPublishingRecoverer(templatesByValueType),
			FixedBackOff(RETRY_INTERVAL_MILLIS, RETRY_ATTEMPTS),
		)
	}

	@Bean(DEAD_LETTER_CONTAINER_FACTORY)
	fun deadLetterListenerContainerFactory(
		consumerFactory: ConsumerFactory<Any, Any>,
		alerter: DeadLetterAlerter,
	): ConcurrentKafkaListenerContainerFactory<String, ByteArray> =
		ConcurrentKafkaListenerContainerFactory<String, ByteArray>().apply {
			setConsumerFactory(
				DefaultKafkaConsumerFactory(
					consumerFactory.configurationProperties + mapOf(
						ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG to StringDeserializer::class.java,
						ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG to ByteArrayDeserializer::class.java,
					),
				),
			)
			setCommonErrorHandler(
				DefaultErrorHandler(
					{ record, cause ->
						alerter.alert(
							"[쿠폰 발급 DLT] 기록 실패 — 재시도 한도를 넘겨 건너뜀, 확인 필요. " +
								"partition=${record.partition()} offset=${record.offset()} 원인=${(cause.cause ?: cause).javaClass.name}",
						)
					},
					ExponentialBackOff(DEAD_LETTER_RETRY_INITIAL_MILLIS, DEAD_LETTER_RETRY_MULTIPLIER).apply {
						maxInterval = DEAD_LETTER_RETRY_MAX_INTERVAL_MILLIS
						maxElapsedTime = DEAD_LETTER_RETRY_MAX_ELAPSED_MILLIS
					},
				),
			)
		}

	private fun issuanceTopic(name: String): NewTopic =
		TopicBuilder.name(name)
			.partitions(ISSUANCE_PARTITIONS)
			.replicas(replicas)
			.config(TopicConfig.MIN_IN_SYNC_REPLICAS_CONFIG, minInSyncReplicas().toString())
			.config(TopicConfig.RETENTION_MS_CONFIG, ISSUANCE_RETENTION_MILLIS.toString())
			.build()

	private fun minInSyncReplicas(): Int = if (replicas > 1) 2 else 1

	companion object {
		const val ISSUANCE_REQUESTED_TOPIC = "coupon.issuance.requested"
		const val ISSUANCE_REQUESTED_DLT = "$ISSUANCE_REQUESTED_TOPIC-dlt"
		const val ISSUANCE_WRITER_GROUP = "coupon-issuance-writer"
		const val ISSUANCE_WRITER_CONCURRENCY = "3"
		const val ISSUANCE_DEAD_LETTER_GROUP = "coupon-issuance-dead-letter"
		const val DEAD_LETTER_CONTAINER_FACTORY = "deadLetterListenerContainerFactory"
		const val REPLAY_HEADER = "coupon-dead-letter-replay"
		private const val DEAD_LETTER_RETRY_INITIAL_MILLIS = 5_000L
		private const val DEAD_LETTER_RETRY_MULTIPLIER = 2.0
		private const val DEAD_LETTER_RETRY_MAX_INTERVAL_MILLIS = 60_000L
		private const val DEAD_LETTER_RETRY_MAX_ELAPSED_MILLIS = 30L * 60 * 1000
		const val ISSUANCE_RETENTION_MILLIS = 7L * 24 * 60 * 60 * 1000
		private const val ISSUANCE_PARTITIONS = 3
		private const val RETRY_INTERVAL_MILLIS = 1_000L
		private const val RETRY_ATTEMPTS = 3L
	}
}
