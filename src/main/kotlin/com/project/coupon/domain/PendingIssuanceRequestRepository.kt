package com.project.coupon.domain

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import org.springframework.data.repository.query.Param

interface PendingIssuanceRequestRepository : JpaRepository<PendingIssuanceRequest, String> {

	@Query(
		value = "select * from pending_issuance_request order by created_at limit :limit for update skip locked",
		nativeQuery = true,
	)
	fun lockOldest(@Param("limit") limit: Int): List<PendingIssuanceRequest>
}
