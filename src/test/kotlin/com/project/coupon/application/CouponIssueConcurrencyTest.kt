package com.project.coupon.application

import com.project.coupon.FixedClockConfiguration
import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.domain.Coupon
import com.project.coupon.domain.CouponRepository
import com.project.coupon.domain.IssuanceRepository
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

@SpringBootTest
@Import(TestcontainersConfiguration::class, FixedClockConfiguration::class)
class CouponIssueConcurrencyTest(
	private val couponService: CouponService,
	private val couponRepository: CouponRepository,
	private val issuanceRepository: IssuanceRepository,
) : FunSpec({

	afterTest {
		issuanceRepository.deleteAll()
		couponRepository.deleteAll()
	}

	fun createCoupon(totalQuantity: Int): Long =
		couponRepository.save(
			Coupon(
				name = "선착순",
				totalQuantity = totalQuantity,
				validityDays = 7,
				createdAt = FixedClockConfiguration.NOW,
			)
		).id.shouldNotBeNull()

	// 모든 워커를 한 시점에 풀어 같은 행에 몰리게 한다 — 결과마다 성공이면 null, 실패면 그 예외
	// 요청 수만큼 스레드를 만들지 않도록 워커가 큐에서 사용자를 꺼내 처리한다
	fun runConcurrently(userIds: List<Long>, couponId: Long, threads: Int = userIds.size): List<Throwable?> {
		val pending = ConcurrentLinkedQueue(userIds)
		val pool = Executors.newFixedThreadPool(threads)
		val ready = CountDownLatch(threads)
		val start = CountDownLatch(1)
		val done = CountDownLatch(threads)
		val results = ConcurrentLinkedQueue<Result<Unit>>()
		try {
			repeat(threads) {
				pool.execute {
					try {
						ready.countDown()
						start.await()
						while (true) {
							val userId = pending.poll() ?: break
							results += runCatching { couponService.issue(couponId, userId) }.map { }
						}
					} finally {
						done.countDown()
					}
				}
			}
			ready.await()
			start.countDown()
			done.await(60, TimeUnit.SECONDS) shouldBe true
			return results.map { it.exceptionOrNull() }
		} finally {
			pool.shutdownNow()
		}
	}

	test("서로 다른 사용자 100명이 총수량 10 인 행사에 동시에 요청하면 정확히 10건만 발급되고 발급 수량과 발급 내역 수가 같다") {
		val couponId = createCoupon(totalQuantity = 10)

		val results = runConcurrently((1L..100L).toList(), couponId)

		assertSoftly {
			results.count { it == null } shouldBe 10
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("SoldOutException")
			issuanceRepository.count() shouldBe 10
			couponRepository.findById(couponId).get().issuedQuantity shouldBe 10
		}
	}

	test("서로 다른 사용자 5,000명이 총수량 1,000 인 행사에 동시에 요청하면 정확히 1,000건만 발급되고 발급 수량과 발급 내역 수가 같다") {
		val couponId = createCoupon(totalQuantity = 1_000)

		val results = runConcurrently((1L..5_000L).toList(), couponId, threads = 200)

		assertSoftly {
			results.size shouldBe 5_000
			results.count { it == null } shouldBe 1_000
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("SoldOutException")
			issuanceRepository.count() shouldBe 1_000
			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1_000
		}
	}

	test("같은 사용자가 같은 행사에 동시에 20번 요청하면 1건만 발급되고 나머지는 모두 AlreadyIssuedException 이다") {
		val couponId = createCoupon(totalQuantity = 10_000)

		val results = runConcurrently(List(20) { 42L }, couponId)

		assertSoftly {
			results.count { it == null } shouldBe 1
			results.filterNotNull().map { it::class.simpleName }.toSet() shouldBe setOf("AlreadyIssuedException")
			issuanceRepository.count() shouldBe 1
			couponRepository.findById(couponId).get().issuedQuantity shouldBe 1
		}
	}
})
