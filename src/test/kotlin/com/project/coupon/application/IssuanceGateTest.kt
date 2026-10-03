package com.project.coupon.application

import com.project.coupon.TestcontainersConfiguration
import com.project.coupon.runConcurrently
import io.kotest.assertions.assertSoftly
import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import org.springframework.data.redis.core.StringRedisTemplate

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class IssuanceGateTest(
	private val gate: IssuanceGate,
	redisTemplate: StringRedisTemplate,
) : FunSpec({

	val couponId = 1L
	val probe = IssuanceGateProbe(redisTemplate)

	// 같은 스프링 컨텍스트를 쓰는 다른 스펙이 같은 id 의 키를 남겼을 수 있어 앞뒤로 지운다
	beforeTest { probe.clear(couponId) }
	afterTest { probe.clear(couponId) }

	fun tryPassConcurrently(userIds: List<Long>): List<GateResult> =
		runConcurrently(userIds) { userId -> gate.tryPass(couponId, userId) }.map { it.getOrThrow() }

	test("재고 키가 없으면 판정하지 않고 초기화가 필요하다고 알린다") {
		gate.tryPass(couponId, userId = 1L) shouldBe GateResult.NOT_INITIALIZED
	}

	test("재고가 남아 있으면 통과시키며 재고를 1 줄이고 사용자를 기록한다") {
		gate.initialize(couponId, remaining = 3, issuedUserIds = emptyList())

		gate.tryPass(couponId, userId = 1L) shouldBe GateResult.PASSED

		assertSoftly {
			probe.stock(couponId) shouldBe "2"
			probe.isMember(couponId, 1L) shouldBe true
		}
	}

	test("이미 통과한 사용자는 재고와 상관없이 중복으로 거절하고 재고를 줄이지 않는다") {
		gate.initialize(couponId, remaining = 3, issuedUserIds = emptyList())
		gate.tryPass(couponId, userId = 1L)

		gate.tryPass(couponId, userId = 1L) shouldBe GateResult.DUPLICATE
		probe.stock(couponId) shouldBe "2"
	}

	test("재고가 0 이면 매진으로 거절하고 사용자를 기록하지 않는다") {
		gate.initialize(couponId, remaining = 1, issuedUserIds = emptyList())
		gate.tryPass(couponId, userId = 1L)

		gate.tryPass(couponId, userId = 2L) shouldBe GateResult.SOLD_OUT

		assertSoftly {
			probe.stock(couponId) shouldBe "0"
			probe.isMember(couponId, 2L) shouldBe false
		}
	}

	test("통과를 되돌리면 재고를 1 되돌리고 사용자를 지우며, 두 번 되돌려도 한 번만 되돌린다") {
		gate.initialize(couponId, remaining = 1, issuedUserIds = emptyList())
		gate.tryPass(couponId, userId = 1L)

		val first = gate.release(couponId, userId = 1L)
		val second = gate.release(couponId, userId = 1L)

		assertSoftly {
			first shouldBe true
			second shouldBe false
			probe.stock(couponId) shouldBe "1"
			probe.isMember(couponId, 1L) shouldBe false
		}
	}

	test("재고만 되돌리면 재고를 1 늘리고 사용자 기록은 남긴다") {
		gate.initialize(couponId, remaining = 3, issuedUserIds = emptyList())
		gate.tryPass(couponId, userId = 1L)

		gate.restoreStock(couponId) shouldBe true

		assertSoftly {
			probe.stock(couponId) shouldBe "3"
			probe.isMember(couponId, 1L) shouldBe true
		}
	}

	test("매진으로 표시하면 재고를 0 으로 만들고 사용자를 지운다") {
		gate.initialize(couponId, remaining = 5, issuedUserIds = emptyList())
		gate.tryPass(couponId, userId = 1L)

		gate.markSoldOut(couponId, userId = 1L) shouldBe true

		assertSoftly {
			probe.stock(couponId) shouldBe "0"
			probe.isMember(couponId, 1L) shouldBe false
			gate.tryPass(couponId, userId = 2L) shouldBe GateResult.SOLD_OUT
		}
	}

	test("재고 키가 없으면 어떤 보상도 재고 키를 새로 만들지 않는다") {
		probe.rememberUserOnly(couponId, userId = 1L)

		assertSoftly {
			gate.release(couponId, userId = 1L) shouldBe false
			gate.restoreStock(couponId) shouldBe false
			gate.markSoldOut(couponId, userId = 1L) shouldBe false
			probe.stock(couponId) shouldBe null
		}
	}

	test("초기화는 재고 키가 없을 때만 하고, 이미 발급된 사용자를 함께 기록한다") {
		val first = gate.initialize(couponId, remaining = 8, issuedUserIds = listOf(1L, 2L))
		val second = gate.initialize(couponId, remaining = 100, issuedUserIds = emptyList())

		assertSoftly {
			first shouldBe true
			second shouldBe false
			probe.stock(couponId) shouldBe "8"
			gate.tryPass(couponId, userId = 2L) shouldBe GateResult.DUPLICATE
		}
	}

	// unpack 은 인자 수에 상한이 있어 initialize.lua 가 나눠서 SADD 한다 — 그 경계를 넘는 수로 확인한다
	test("이미 발급된 사용자가 많아도 모두 기록된다") {
		gate.initialize(couponId, remaining = 0, issuedUserIds = (1L..10_000L).toList())

		probe.userCount(couponId) shouldBe 10_000L
	}

	test("서로 다른 사용자 100명이 재고 10 에 동시에 요청하면 정확히 10명만 통과한다") {
		gate.initialize(couponId, remaining = 10, issuedUserIds = emptyList())

		val results = tryPassConcurrently((1L..100L).toList())

		assertSoftly {
			results.count { it == GateResult.PASSED } shouldBe 10
			results.count { it == GateResult.SOLD_OUT } shouldBe 90
			probe.stock(couponId) shouldBe "0"
			probe.userCount(couponId) shouldBe 10L
		}
	}

	test("같은 사용자가 재고 10 에 동시에 100번 요청하면 1번만 통과하고 재고는 1만 줄어든다") {
		gate.initialize(couponId, remaining = 10, issuedUserIds = emptyList())

		val results = tryPassConcurrently(List(100) { 42L })

		assertSoftly {
			results.count { it == GateResult.PASSED } shouldBe 1
			results.count { it == GateResult.DUPLICATE } shouldBe 99
			probe.stock(couponId) shouldBe "9"
			probe.userCount(couponId) shouldBe 1L
		}
	}
})
