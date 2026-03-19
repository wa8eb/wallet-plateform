package com.walletdomain.application.command

import com.walletdomain.domain.model.*

data class CreateWalletCommand(val userId: UserId, val currencyCode: String)

data class CreatePocketCommand(
    val walletId: WalletId,
    val name: String,
    val allowedBenefitCodes: Set<String>,       // e.g. setOf("FOOD", "TRANSPORT")
)

data class CreditPocketCommand(
    val walletId: WalletId,
    val pocketId: PocketId,
    val amount: Long,
    val currencyCode: String,
    val source: CreditSource,
    val reference: String,
)

data class SpendCommand(
    val walletId: WalletId,
    val pocketId: PocketId,
    val amount: Long,
    val currencyCode: String,
    val merchant: String,
    val benefitCategoryCode: String,            // renamed from categoryCode
    val reference: String,
)

data class TransferCommand(
    val walletId: WalletId,
    val fromPocketId: PocketId,
    val toPocketId: PocketId,
    val amount: Long,
    val currencyCode: String,
    val reference: String,
)

data class DeactivatePocketCommand(val walletId: WalletId, val pocketId: PocketId)

data class CloseWalletCommand(val walletId: WalletId)
