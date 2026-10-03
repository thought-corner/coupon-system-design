package com.project.coupon.application

interface DeadLetterAlerter {
	fun alert(message: String): Boolean
}
