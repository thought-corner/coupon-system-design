package com.project.coupon

import com.project.coupon.support.ClockConfig
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import java.time.Clock
import java.time.LocalDateTime

// 통합 테스트에서 "지금"을 고정한다 — 운영 Clock 빈(ClockConfig) 대신 이 빈이 주입된다
@TestConfiguration(proxyBeanMethods = false)
class FixedClockConfiguration {

	@Bean
	@Primary
	fun fixedClock(): Clock = Clock.fixed(NOW.atZone(ClockConfig.SERVICE_ZONE).toInstant(), ClockConfig.SERVICE_ZONE)

	companion object {
		val NOW: LocalDateTime = LocalDateTime.of(2026, 10, 3, 10, 0, 0)
	}
}
