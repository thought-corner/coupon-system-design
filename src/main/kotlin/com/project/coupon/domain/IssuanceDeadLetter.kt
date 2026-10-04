package com.project.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.GeneratedValue
import jakarta.persistence.GenerationType
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import jakarta.persistence.UniqueConstraint
import java.time.LocalDateTime

@Entity
@Table(
	name = "issuance_dead_letter",
	uniqueConstraints = [UniqueConstraint(name = "uk_issuance_dead_letter_message", columnNames = ["message_id"])],
	indexes = [Index(name = "idx_issuance_dead_letter_status", columnList = "status")],
)
class IssuanceDeadLetter(
	@Column(name = "message_id", length = 36)
	var messageId: String?,

	@Column(name = "coupon_id")
	var couponId: Long?,

	@Column(name = "user_id")
	var userId: Long?,

	@Column(nullable = false, columnDefinition = "mediumblob")
	var payload: ByteArray,

	@Column(name = "failure_type", nullable = false)
	var failureType: String,

	@Column(name = "failure_reason", nullable = false, length = FAILURE_REASON_MAX_LENGTH)
	var failureReason: String,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	var status: DeadLetterStatus,

	@Column(name = "dead_letter_count", nullable = false)
	var deadLetterCount: Int,

	@Column(name = "replay_attempts", nullable = false)
	var replayAttempts: Int = 0,

	@Column(name = "replay_failures", nullable = false)
	var replayFailures: Int = 0,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: LocalDateTime,

	@Column(name = "updated_at", nullable = false)
	var updatedAt: LocalDateTime,

	@Column(name = "alerted_at")
	var alertedAt: LocalDateTime? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,
) {
	companion object {
		const val FAILURE_REASON_MAX_LENGTH = 1000
	}
}
