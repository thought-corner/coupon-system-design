package com.project.coupon.application

import com.project.coupon.domain.Issuance
import com.project.coupon.domain.IssuanceRepository
import com.project.coupon.support.IssuanceNotFoundException
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import java.time.Clock
import java.time.LocalDateTime

@Service
class IssuanceService(
	private val issuanceRepository: IssuanceRepository,
	private val clock: Clock,
) {

	@Transactional
	fun use(issuanceId: Long, userId: Long): Issuance {
		val issuance = issuanceRepository.findById(issuanceId)
			.orElseThrow { IssuanceNotFoundException() }

		issuance.use(userId, LocalDateTime.now(clock))
		return issuance
	}
}
