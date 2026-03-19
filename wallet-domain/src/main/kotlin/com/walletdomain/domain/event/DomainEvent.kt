package com.walletdomain.domain.event

import com.walletdomain.domain.model.*
import java.time.Instant
import java.util.UUID

sealed class DomainEvent {
    abstract val eventId: UUID
    abstract val occurredAt: Instant

    // Wallet lifecycle
    data class WalletCreated(val walletId: WalletId, val userId: UserId, val currency: Currency,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class WalletClosed(val walletId: WalletId, val userId: UserId,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    // Pocket lifecycle
    data class PocketCreated(val walletId: WalletId, val pocketId: PocketId, val name: String,
        val allowedBenefits: Set<BenefitCategory>,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class PocketDeactivated(val walletId: WalletId, val pocketId: PocketId,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    // Financial events
    data class PocketCredited(val walletId: WalletId, val pocketId: PocketId, val amount: Money,
        val source: CreditSource, val reference: String,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class PocketDebited(val walletId: WalletId, val pocketId: PocketId, val amount: Money,
        val merchant: String, val benefitCategory: BenefitCategory, val reference: String,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class PocketTransferExecuted(val walletId: WalletId, val fromPocketId: PocketId,
        val toPocketId: PocketId, val amount: Money, val reference: String,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    // Rules engine events
    data class OperationRejectedByPolicy(
        val walletId: WalletId, val operationType: String,
        val rejectedByRule: String, val reason: String, val reference: String,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class OverspendSplitExecuted(
        val walletId: WalletId, val primaryPocketId: PocketId, val coverPocketId: PocketId,
        val primaryAmount: Money, val coverAmount: Money, val merchant: String, val reference: String,
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()

    data class RuleNotificationRaised(
        val walletId: WalletId, val ruleName: String, val reason: String,
        val payload: Map<String, String> = emptyMap(),
        override val eventId: UUID = UUID.randomUUID(), override val occurredAt: Instant = Instant.now()) : DomainEvent()
}
