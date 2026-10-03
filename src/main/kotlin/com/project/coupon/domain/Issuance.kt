package com.project.coupon.domain

import com.project.coupon.support.AlreadyUsedException
import com.project.coupon.support.ExpiredException
import com.project.coupon.support.NotOwnerException
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
	name = "issuance",
	uniqueConstraints = [
		UniqueConstraint(
			name = "uk_issuance_user_coupon",
			columnNames = ["user_id", "coupon_id"],
		),
	],
	indexes = [
		Index(name = "idx_issuance_status", columnList = "status"),
		Index(name = "idx_issuance_coupon", columnList = "coupon_id"),
	],
)
class Issuance(
	@Column(name = "user_id", nullable = false)
	var userId: Long,

	@Column(name = "coupon_id", nullable = false)
	var couponId: Long,

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 16)
	var status: IssuanceStatus = IssuanceStatus.ISSUED,

	@Column(name = "issued_at", nullable = false, updatable = false)
	var issuedAt: LocalDateTime,

	@Column(name = "expires_at", nullable = false)
	var expiresAt: LocalDateTime,

	@Column(name = "used_at")
	var usedAt: LocalDateTime? = null,

	@Column(name = "message_id", length = 36)
	var messageId: String? = null,

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	var id: Long? = null,
) {
	fun isExpired(now: LocalDateTime): Boolean = now >= expiresAt

	fun use(userId: Long, now: LocalDateTime) {
		if (this.userId != userId) {
			throw NotOwnerException()
		}
		if (status == IssuanceStatus.USED) {
			throw AlreadyUsedException()
		}
		if (isExpired(now)) {
			throw ExpiredException()
		}

		status = IssuanceStatus.USED
		usedAt = now
	}
}
