package com.project.coupon.application

import org.springframework.core.io.ClassPathResource
import org.springframework.data.redis.core.StringRedisTemplate
import org.springframework.data.redis.core.script.RedisScript
import org.springframework.stereotype.Component

@Component
class IssuanceGate(
	private val redisTemplate: StringRedisTemplate,
) {

	private val issueScript = script("lua/issue.lua")
	private val initializeScript = script("lua/initialize.lua")
	private val releaseScript = script("lua/release.lua")
	private val restoreStockScript = script("lua/restore_stock.lua")
	private val markSoldOutScript = script("lua/mark_sold_out.lua")

	fun tryPass(couponId: Long, userId: Long): GateResult =
		GateResult.of(run(issueScript, couponId, userId.toString()))

	fun initialize(couponId: Long, remaining: Int, issuedUserIds: Collection<Long>): Boolean =
		run(
			initializeScript,
			couponId,
			remaining.toString(),
			*issuedUserIds.map(Long::toString).toTypedArray(),
		) == APPLIED

	fun release(couponId: Long, userId: Long): Boolean =
		run(releaseScript, couponId, userId.toString()) == APPLIED

	fun restoreStock(couponId: Long): Boolean =
		run(restoreStockScript, couponId) == APPLIED

	fun markSoldOut(couponId: Long, userId: Long): Boolean =
		run(markSoldOutScript, couponId, userId.toString()) == APPLIED

	private fun run(script: RedisScript<Long>, couponId: Long, vararg args: String): Long =
		redisTemplate.execute(script, keys(couponId), *args)
			?: error("Lua 스크립트 결과가 없습니다")

	private fun keys(couponId: Long): List<String> =
		listOf(stockKey(couponId), usersKey(couponId))

	private fun script(path: String): RedisScript<Long> =
		RedisScript.of(ClassPathResource(path), Long::class.java)

	companion object {
		private const val APPLIED = 1L

		fun stockKey(couponId: Long) = "coupon:{$couponId}:stock"
		fun usersKey(couponId: Long) = "coupon:{$couponId}:users"
	}
}
