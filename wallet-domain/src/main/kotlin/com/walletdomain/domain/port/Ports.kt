package com.walletdomain.domain.port

import com.walletdomain.domain.event.DomainEvent
import com.walletdomain.domain.model.UserId
import com.walletdomain.domain.model.Wallet
import com.walletdomain.domain.model.WalletId

// ---------------------------------------------------------------------------
// Output Ports — interfaces only, no implementation in the domain
// ---------------------------------------------------------------------------

/**
 * Wallet repository port.
 * Implementations live in infrastructure layers (in-memory, JPA, R2DBC, etc.)
 */
interface WalletRepository {
    fun save(wallet: Wallet): Wallet
    fun findById(walletId: WalletId): Wallet?
    fun findByUserId(userId: UserId): Wallet?
    fun existsByUserId(userId: UserId): Boolean
    fun delete(walletId: WalletId)
}

/**
 * Domain event publisher port.
 * Implementations could be in-memory bus, Kafka, SNS, etc.
 */
interface DomainEventPublisher {
    fun publishAll(events: List<DomainEvent>)
    fun publish(event: DomainEvent) = publishAll(listOf(event))
}

/**
 * No-op publisher for contexts that don't need event propagation.
 */
object NoOpEventPublisher : DomainEventPublisher {
    override fun publishAll(events: List<DomainEvent>) { /* intentionally empty */ }
}
