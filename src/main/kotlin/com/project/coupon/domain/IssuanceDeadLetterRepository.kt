package com.project.coupon.domain

import jakarta.persistence.LockModeType
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Lock
import org.springframework.data.jpa.repository.Modifying
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param
import java.time.LocalDateTime

interface IssuanceDeadLetterRepository : JpaRepository<IssuanceDeadLetter, Long> {
	fun findByMessageId(messageId: String): IssuanceDeadLetter?

	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select d from IssuanceDeadLetter d where d.messageId = :messageId")
	fun findByMessageIdForUpdate(@Param("messageId") messageId: String): IssuanceDeadLetter?

	@Query(
		value = "select * from issuance_dead_letter " +
			"where (status = 'PENDING_REPLAY' and updated_at <= :cutoff) or (status = 'REPLAYING' and updated_at <= :staleCutoff) " +
			"order by updated_at limit :limit for update skip locked",
		nativeQuery = true,
	)
	fun lockDueForReplay(
		@Param("cutoff") cutoff: LocalDateTime,
		@Param("staleCutoff") staleCutoff: LocalDateTime,
		@Param("limit") limit: Int,
	): List<IssuanceDeadLetter>

	@Query(
		value = "select * from issuance_dead_letter where status in ('ALERTED', 'UNREADABLE') and alerted_at is null " +
			"order by id limit :limit for update skip locked",
		nativeQuery = true,
	)
	fun lockUnalerted(@Param("limit") limit: Int): List<IssuanceDeadLetter>

	@Modifying
	@Query(
		"update IssuanceDeadLetter d set d.status = com.project.coupon.domain.DeadLetterStatus.PENDING_REPLAY, d.updatedAt = :now " +
			"where d.id in :ids and d.status = com.project.coupon.domain.DeadLetterStatus.REPLAYING",
	)
	fun returnToPendingReplay(@Param("ids") ids: Collection<Long>, @Param("now") now: LocalDateTime): Int

	@Modifying
	@Query(
		"update IssuanceDeadLetter d set d.status = com.project.coupon.domain.DeadLetterStatus.RESOLVED, d.updatedAt = :now " +
			"where d.messageId = :messageId and d.status = com.project.coupon.domain.DeadLetterStatus.REPLAYING",
	)
	fun resolveReplaying(@Param("messageId") messageId: String, @Param("now") now: LocalDateTime): Int
}
