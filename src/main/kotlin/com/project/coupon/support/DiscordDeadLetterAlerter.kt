package com.project.coupon.support

import com.project.coupon.application.DeadLetterAlerter
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.http.MediaType
import org.springframework.http.client.JdkClientHttpRequestFactory
import org.springframework.stereotype.Component
import org.springframework.web.client.RestClient
import java.net.http.HttpClient
import java.time.Duration

@Component
class DiscordDeadLetterAlerter(
	@param:Value("\${coupon.alert.discord.webhook-url}") private val webhookUrl: String,
) : DeadLetterAlerter {

	private val restClient = RestClient.builder()
		.requestFactory(
			JdkClientHttpRequestFactory(HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build())
				.apply { setReadTimeout(READ_TIMEOUT) },
		)
		.build()

	init {
		if (webhookUrl.isBlank()) {
			log.warn("디스코드 웹훅 URL 이 없어 DLT 알림은 ERROR 로그로만 남는다")
		}
	}

	override fun alert(message: String): Boolean {
		log.error("[DLT 알림] {}", message)
		if (webhookUrl.isBlank()) {
			return true
		}
		return runCatching {
			restClient.post()
				.uri(webhookUrl)
				.contentType(MediaType.APPLICATION_JSON)
				.body(mapOf("content" to message.take(DISCORD_CONTENT_MAX_LENGTH)))
				.retrieve()
				.toBodilessEntity()
		}.onFailure {
			log.error("디스코드 알림 전송 실패 원인={}", it.javaClass.name)
		}.isSuccess
	}

	companion object {
		private val log = LoggerFactory.getLogger(DiscordDeadLetterAlerter::class.java)
		private const val DISCORD_CONTENT_MAX_LENGTH = 2000
		private val CONNECT_TIMEOUT = Duration.ofSeconds(3)
		private val READ_TIMEOUT = Duration.ofSeconds(5)
	}
}
