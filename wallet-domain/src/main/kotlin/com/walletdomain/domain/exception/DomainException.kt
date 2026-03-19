package com.walletdomain.domain.exception

import com.walletdomain.domain.model.*

// ---------------------------------------------------------------------------
// Domain Exceptions — pure, no HTTP semantics here
// ---------------------------------------------------------------------------

sealed class DomainException(message: String, cause: Throwable? = null) : RuntimeException(message, cause) {

    class WalletNotFound(walletId: WalletId) :
        DomainException("Wallet not found: $walletId")

    class WalletInactive(walletId: WalletId) :
        DomainException("Wallet is closed: $walletId")

    class WalletAlreadyExists(userId: UserId) :
        DomainException("Wallet already exists for user: $userId")

    class PocketNotFound(pocketId: PocketId) :
        DomainException("Pocket not found: $pocketId")

    class PocketInactive(pocketId: PocketId) :
        DomainException("Pocket is inactive: $pocketId")

    class InsufficientFunds(pocketId: PocketId, available: Money, requested: Money) :
        DomainException("Insufficient funds in pocket $pocketId: available=$available, requested=$requested")

    class CategoryNotAllowed(pocketId: PocketId, category: BenefitCategory) :
        DomainException("Pocket $pocketId does not allow spending on category ${category.code}")

    class InvalidOperation(message: String) :
        DomainException(message)
}
