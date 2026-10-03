package com.project.coupon.domain

enum class DeadLetterStatus {
	PENDING_REPLAY,
	REPLAYING,
	ALERTED,
	UNREADABLE,
	DISCARDED,
	RESOLVED,
}
