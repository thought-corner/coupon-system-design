package com.project.coupon.domain

import jakarta.persistence.Column
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Index
import jakarta.persistence.Table
import java.time.LocalDateTime

@Entity
@Table(
	name = "pending_issuance_request",
	indexes = [Index(name = "idx_pending_issuance_request_created", columnList = "created_at")],
)
class PendingIssuanceRequest(
	@Id
	@Column(name = "message_id", length = 36)
	var messageId: String,

	@Column(name = "coupon_id", nullable = false)
	var couponId: Long,

	@Column(name = "user_id", nullable = false)
	var userId: Long,

	@Column(name = "created_at", nullable = false, updatable = false)
	var createdAt: LocalDateTime,

	@Column(nullable = false)
	var attempts: Int = 0,
)
