package com.project.coupon.support

import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.scheduling.annotation.EnableAsync
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor

@Configuration
@EnableAsync
class AsyncConfig {

	@Bean(ISSUANCE_EXECUTOR)
	fun issuanceExecutor(): ThreadPoolTaskExecutor =
		ThreadPoolTaskExecutor().apply {
			corePoolSize = ISSUANCE_WORKERS
			maxPoolSize = ISSUANCE_WORKERS
			queueCapacity = ISSUANCE_QUEUE_CAPACITY
			setThreadNamePrefix("issuance-")
			setWaitForTasksToCompleteOnShutdown(true)
		}

	@Bean
	fun issuanceExecutorDrainer(@Qualifier(ISSUANCE_EXECUTOR) issuanceExecutor: ThreadPoolTaskExecutor): ExecutorDrainer =
		ExecutorDrainer(issuanceExecutor, SHUTDOWN_AWAIT_SECONDS)

	companion object {
		const val ISSUANCE_EXECUTOR = "issuanceExecutor"
		private const val ISSUANCE_WORKERS = 10
		private const val ISSUANCE_QUEUE_CAPACITY = 10_000
		private const val SHUTDOWN_AWAIT_SECONDS = 30L
	}
}
