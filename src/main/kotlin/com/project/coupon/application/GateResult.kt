package com.project.coupon.application

enum class GateResult(val code: Long) {
	PASSED(1),
	SOLD_OUT(0),
	DUPLICATE(-1),
	NOT_INITIALIZED(-2),
	;

	companion object {
		fun of(code: Long): GateResult =
			entries.firstOrNull { it.code == code }
				?: error("issue.lua 가 알 수 없는 결과를 돌려줬습니다: $code")
	}
}
