package com.project.coupon.support

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import java.time.Clock
import java.time.ZoneId

@Configuration
class ClockConfig {

	@Bean
	fun clock(): Clock = Clock.system(SERVICE_ZONE)

	companion object {
		val SERVICE_ZONE: ZoneId = ZoneId.of("Asia/Seoul")
	}
}
