package com.project.coupon

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.springframework.test.context.DynamicPropertyRegistrar
import org.testcontainers.containers.GenericContainer
import org.testcontainers.kafka.KafkaContainer
import org.testcontainers.mysql.MySQLContainer

// 통합 테스트용 MySQL·Redis·Kafka — @ServiceConnection 이 컨테이너 접속 정보로 datasource·redis·kafka 를 덮어쓴다(application.yaml 의 localhost 를 쓰지 않는다)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun mysqlContainer(): MySQLContainer = MySQLContainer("mysql:8.4")

	// Redis 는 전용 컨테이너 클래스가 없어 GenericContainer 로 띄우고, name 으로 Redis 접속 정보임을 알린다
	@Bean
	@ServiceConnection(name = "redis")
	fun redisContainer(): GenericContainer<*> = GenericContainer("redis:7.4").withExposedPorts(6379)

	@Bean
	@ServiceConnection
	fun kafkaContainer(): KafkaContainer = KafkaContainer("apache/kafka:3.9.1")

	// 운영 설정은 복제본 수를 env 로만 받아(빠뜨리면 기동 실패) 단일 브로커 테스트에서는 1 을 넣는다
	@Bean
	fun issuanceTopicReplicas(): DynamicPropertyRegistrar =
		DynamicPropertyRegistrar { it.add("coupon.kafka.issuance.replicas") { 1 } }
}
