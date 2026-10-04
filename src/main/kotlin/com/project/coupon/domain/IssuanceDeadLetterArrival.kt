package com.project.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
	name = "issuance_dead_letter_arrival",
	uniqueConstraints = [
		UniqueConstraint(
			name = "uk_issuance_dead_letter_arrival_source",
			columnNames = ["dlt_partition", "dlt_offset", "payload_hash"],
		),
	],
)
class IssuanceDeadLetterArrival(
	@Column(name = "dead_letter_id", nullable = false)
	var deadLetterId: Long,

	@Column(name = "dlt_partition", nullable = false)
	var partition: Int,

	@Column(name = "dlt_offset", nullable = false)
	var offset: Long,

	@Column(name = "payload_hash", nullable = false, length = 64)
	var payloadHash: String,

	@Column(name = "replay_attempt", nullable = false)
	var replayAttempt: Int,

	@Column(name = "arrived_at", nullable = false, updatable = false)
	var arrivedAt: LocalDateTime,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,
)
