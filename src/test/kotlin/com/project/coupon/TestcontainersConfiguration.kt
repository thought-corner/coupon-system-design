package com.project.coupon

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.containers.GenericContainer
import org.testcontainers.mysql.MySQLContainer

// 통합 테스트용 MySQL·Redis — @ServiceConnection 이 컨테이너 접속 정보로 datasource·redis 를 덮어쓴다(application.yaml 의 localhost 를 쓰지 않는다)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun mysqlContainer(): MySQLContainer = MySQLContainer("mysql:8.4")

	// Redis 는 전용 컨테이너 클래스가 없어 GenericContainer 로 띄우고, name 으로 Redis 접속 정보임을 알린다
	@Bean
	@ServiceConnection(name = "redis")
	fun redisContainer(): GenericContainer<*> = GenericContainer("redis:7.4").withExposedPorts(6379)
}
