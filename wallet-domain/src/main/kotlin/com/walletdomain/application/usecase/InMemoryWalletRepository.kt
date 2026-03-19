package com.walletdomain.application.usecase

import com.walletdomain.domain.model.UserId
import com.walletdomain.domain.model.Wallet
import com.walletdomain.domain.model.WalletId
import com.walletdomain.domain.port.WalletRepository
import java.util.concurrent.ConcurrentHashMap

// ---------------------------------------------------------------------------
// In-Memory Repository — onion architecture: swappable with JPA / R2DBC
// ConcurrentHashMap gives us safe concurrent reads; writes are copy-on-write
// via Kotlin data class immutability so no corruption is possible.
// ---------------------------------------------------------------------------

class InMemoryWalletRepository : WalletRepository {

    private val store = ConcurrentHashMap<WalletId, Wallet>()

    override fun save(wallet: Wallet): Wallet {
        store[wallet.id] = wallet
        return wallet
    }

    override fun findById(walletId: WalletId): Wallet? = store[walletId]

    override fun findByUserId(userId: UserId): Wallet? =
        store.values.firstOrNull { it.userId == userId }

    override fun existsByUserId(userId: UserId): Boolean =
        store.values.any { it.userId == userId }

    override fun delete(walletId: WalletId) {
        store.remove(walletId)
    }

    fun count(): Int = store.size

    fun clear() = store.clear()
}
