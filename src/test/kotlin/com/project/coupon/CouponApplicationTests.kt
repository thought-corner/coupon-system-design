package com.project.coupon

import io.kotest.core.spec.style.FunSpec
import io.kotest.matchers.shouldBe
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.context.annotation.Import
import javax.sql.DataSource

@SpringBootTest
@Import(TestcontainersConfiguration::class)
class CouponApplicationTests(
	private val dataSource: DataSource,
) : FunSpec({

	test("애플리케이션 컨텍스트가 MySQL 에 연결된 채로 뜬다") {
		dataSource.connection.use { it.isValid(1) } shouldBe true
	}
})
