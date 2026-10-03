package com.project.coupon.application

import org.springframework.data.redis.core.StringRedisTemplate

// 문지기(Redis) 상태를 테스트에서 직접 보고 어긋나게 만드는 도구 — 운영 코드는 IssuanceGate 만 쓴다
class IssuanceGateProbe(
	private val redisTemplate: StringRedisTemplate,
) {

	fun stock(couponId: Long): String? =
		redisTemplate.opsForValue().get(IssuanceGate.stockKey(couponId))

	fun isMember(couponId: Long, userId: Long): Boolean =
		redisTemplate.opsForSet().isMember(IssuanceGate.usersKey(couponId), userId.toString()) == true

	fun userCount(couponId: Long): Long? =
		redisTemplate.opsForSet().size(IssuanceGate.usersKey(couponId))

	fun forgetUser(couponId: Long, userId: Long) {
		redisTemplate.opsForSet().remove(IssuanceGate.usersKey(couponId), userId.toString())
	}

	fun rememberUserOnly(couponId: Long, userId: Long) {
		redisTemplate.opsForSet().add(IssuanceGate.usersKey(couponId), userId.toString())
	}

	fun clear(couponId: Long) {
		redisTemplate.delete(listOf(IssuanceGate.stockKey(couponId), IssuanceGate.usersKey(couponId)))
	}
}
