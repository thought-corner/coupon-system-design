package com.project.coupon.support

import org.slf4j.LoggerFactory
import org.springframework.context.SmartLifecycle
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.TimeUnit

class ExecutorDrainer(
	private val executor: ThreadPoolTaskExecutor,
	private val awaitSeconds: Long,
) : SmartLifecycle {

	@Volatile
	private var running = false

	override fun start() {
		running = true
	}

	override fun stop() {
		val pool = executor.threadPoolExecutor
		pool.shutdown()
		if (!pool.awaitTermination(awaitSeconds, TimeUnit.SECONDS)) {
			val interrupted = pool.activeCount
			val dropped = pool.shutdownNow().size
			log.error("종료 대기 {}초 초과 — 버린 대기 작업 {}건, 중단한 실행 중 작업 {}건", awaitSeconds, dropped, interrupted)
		}
		running = false
	}

	override fun isRunning(): Boolean = running

	override fun getPhase(): Int = PHASE

	override fun isPauseable(): Boolean = false

	companion object {
		const val PHASE = SmartLifecycle.DEFAULT_PHASE / 2
		private val log = LoggerFactory.getLogger(ExecutorDrainer::class.java)
	}
}
