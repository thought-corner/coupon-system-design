package com.project.coupon.support

import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.shouldBe
import org.springframework.context.SmartLifecycle
import org.springframework.context.support.GenericApplicationContext
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Supplier

private const val ISSUANCE_EXECUTOR = "issuanceExecutor"

class ExecutorDrainerTest : FunSpec({

	test("멈추면 큐에 남은 작업까지 모두 끝낸 뒤 돌아온다") {
		val executor = ThreadPoolTaskExecutor().apply {
			corePoolSize = 1
			maxPoolSize = 1
			queueCapacity = 100
			initialize()
		}
		val done = AtomicInteger()
		repeat(20) {
			executor.execute {
				Thread.sleep(10)
				done.incrementAndGet()
			}
		}

		ExecutorDrainer(executor, awaitSeconds = 10).stop()

		done.get() shouldBe 20
	}

	test("대기 시간을 넘기면 남은 작업을 버리고 돌아오며, 버린 작업은 그 뒤에도 실행되지 않는다") {
		val executor = ThreadPoolTaskExecutor().apply {
			corePoolSize = 1
			maxPoolSize = 1
			queueCapacity = 100
			initialize()
		}
		val done = AtomicInteger()
		repeat(20) {
			executor.execute {
				try {
					Thread.sleep(200)
					done.incrementAndGet()
				} catch (e: InterruptedException) {
					Thread.currentThread().interrupt()
				}
			}
		}

		ExecutorDrainer(executor, awaitSeconds = 1).stop()
		val afterStop = done.get()
		Thread.sleep(500)

		assertSoftly {
			afterStop shouldBeLessThan 20
			done.get() shouldBe afterStop
			executor.threadPoolExecutor.isShutdown shouldBe true
		}
	}

	// 웹 서버(새 요청 차단)보다 늦게, Redis 연결(phase 0)보다 먼저 멈춰야 보상이 Redis 에 닿는다
	test("웹 서버가 멈춘 뒤, phase 0 인 Redis 연결보다 먼저 멈추는 단계에 있다") {
		ExecutorDrainer.PHASE shouldBeGreaterThan 0
		ExecutorDrainer.PHASE shouldBeLessThan SmartLifecycle.DEFAULT_PHASE - 2048
	}

	// 테스트 컨텍스트 캐시는 컨텍스트를 바꿀 때 이전 것을 pause 했다가 다시 쓸 때 restart 한다
	test("컨텍스트를 pause 했다가 restart 해도 실행기가 닫히지 않아 작업을 계속 받는다") {
		val context = GenericApplicationContext().apply {
			registerBean(ISSUANCE_EXECUTOR, ThreadPoolTaskExecutor::class.java, Supplier { ThreadPoolTaskExecutor() })
			registerBean(ExecutorDrainer::class.java, Supplier {
				ExecutorDrainer(getBean(ISSUANCE_EXECUTOR, ThreadPoolTaskExecutor::class.java), awaitSeconds = 5)
			})
			refresh()
		}
		try {
			context.pause()
			context.restart()

			val executor = context.getBean(ISSUANCE_EXECUTOR, ThreadPoolTaskExecutor::class.java)
			val ran = CountDownLatch(1)
			executor.execute { ran.countDown() }

			ran.await(5, TimeUnit.SECONDS) shouldBe true
		} finally {
			context.close()
		}
	}
})
