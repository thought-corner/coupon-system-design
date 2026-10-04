package com.project.coupon.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface IssuanceDeadLetterArrivalRepository : JpaRepository<IssuanceDeadLetterArrival, Long> {
	fun existsByPartitionAndOffsetAndPayloadHash(partition: Int, offset: Long, payloadHash: String): Boolean

	@Query(
		"select count(distinct a.replayAttempt) from IssuanceDeadLetterArrival a " +
			"where a.deadLetterId = :deadLetterId and a.replayAttempt > 0",
	)
	fun countFailedReplays(@Param("deadLetterId") deadLetterId: Long): Long
}
