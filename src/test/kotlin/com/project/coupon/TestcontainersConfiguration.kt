package com.project.coupon

import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.testcontainers.service.connection.ServiceConnection
import org.springframework.context.annotation.Bean
import org.testcontainers.mysql.MySQLContainer

// 통합 테스트용 MySQL — @ServiceConnection 이 컨테이너 접속 정보로 datasource 를 덮어쓴다(application.yaml 의 localhost 를 쓰지 않는다)
@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	fun mysqlContainer(): MySQLContainer = MySQLContainer("mysql:8.4")
}
