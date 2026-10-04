package com.project.coupon

import io.kotest.assertions.nondeterministic.eventually
import io.kotest.matchers.shouldBe
import org.springframework.kafka.config.KafkaListenerEndpointRegistry
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer
import org.springframework.kafka.listener.MessageListenerContainer
import kotlin.time.Duration.Companion.seconds

// 컨텍스트가 막 뜬 직후엔 동시 컨슈머가 차례로 합류하며 리밸런스가 일어난다
// 리밸런스는 에러 핸들러가 메모리에 든 재시도 횟수를 지워, "N 번 실패하면 DLT" 를 세는 테스트가 어긋난다 — 모든 컨슈머가 파티션을 받은 뒤 시작한다
suspend fun awaitListenersAssigned(registry: KafkaListenerEndpointRegistry, partitionsPerTopic: Int) {
	eventually(60.seconds) {
		registry.listenerContainers.forEach { container ->
			container.isFullyAssigned(partitionsPerTopic) shouldBe true
		}
	}
}

private fun MessageListenerContainer.isFullyAssigned(partitionsPerTopic: Int): Boolean {
	val consumers = (this as? ConcurrentMessageListenerContainer<*, *>)?.containers ?: listOf(this)
	val assigned = consumers.map { it.assignedPartitions.orEmpty().size }
	return assigned.all { it > 0 } && assigned.sum() == partitionsPerTopic
}
