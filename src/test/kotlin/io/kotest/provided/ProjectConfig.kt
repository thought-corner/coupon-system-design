package io.kotest.provided

import io.kotest.core.config.AbstractProjectConfig
import io.kotest.extensions.spring.SpringExtension

// Kotest 는 이 패키지·이름의 설정을 자동으로 찾는다.
// SpringExtension 을 여기(프로젝트 단위)에 둬야 @SpringBootTest 스펙의 생성자 주입이 된다 — 스펙 안에서 등록하면 인스턴스 생성 뒤라 늦다.
class ProjectConfig : AbstractProjectConfig() {
	override val extensions = listOf(SpringExtension())
}
