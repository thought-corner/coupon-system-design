package com.project.coupon

import io.kotest.matchers.shouldBe
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

// 모든 워커를 한 시점에 풀어 같은 자원에 몰리게 한다 — 입력마다 결과(성공 값 또는 예외) 하나
// 입력 수만큼 스레드를 만들지 않도록 워커가 큐에서 입력을 꺼내 처리한다
fun <T : Any, R> runConcurrently(
	inputs: List<T>,
	threads: Int = inputs.size,
	action: (T) -> R,
): List<Result<R>> {
	val pending = ConcurrentLinkedQueue(inputs)
	val pool = Executors.newFixedThreadPool(threads)
	val ready = CountDownLatch(threads)
	val start = CountDownLatch(1)
	val done = CountDownLatch(threads)
	val results = ConcurrentLinkedQueue<Result<R>>()
	try {
		repeat(threads) {
			pool.execute {
				try {
					ready.countDown()
					start.await()
					while (true) {
						val input = pending.poll() ?: break
						results += runCatching { action(input) }
					}
				} finally {
					done.countDown()
				}
			}
		}
		ready.await()
		start.countDown()
		done.await(60, TimeUnit.SECONDS) shouldBe true
		return results.toList()
	} finally {
		pool.shutdownNow()
	}
}
