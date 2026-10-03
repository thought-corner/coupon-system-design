package com.project.coupon.domain

import org.springframework.data.jpa.repository.JpaRepository

interface IssuanceDeadLetterArrivalRepository : JpaRepository<IssuanceDeadLetterArrival, Long> {
	fun existsByPartitionAndOffsetAndPayloadHash(partition: Int, offset: Long, payloadHash: String): Boolean
}
