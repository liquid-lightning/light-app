package app.light.wallet.ui.sheets

import app.light.wallet.core.InvoiceEntry
import app.light.wallet.core.PayListEntry
import app.light.wallet.core.PeerChannel

/** Every bottom sheet in the app, mirroring the design's sheet layer. */
sealed interface WalletSheet {
    data object Receive : WalletSheet                       // on-chain newaddr + QR
    data object SendChain : WalletSheet                     // on-chain withdraw
    data object NewInvoice : WalletSheet                    // invoice creation
    /**
     * [wrapped] is non-null for an asset invoice (a self-contained string
     * carrying the asset it requires, since BOLT11 has none) - share/scan
     * that instead of [bolt11] in that case.
     */
    data class NewInvoiceResult(
        val bolt11: String,
        val paymentHash: String,
        val expiresAt: ULong,
        val wrapped: String? = null,
    ) : WalletSheet
    data class Pay(val prefill: String = "") : WalletSheet  // xpay
    data object OpenChannel : WalletSheet
    data class InvoiceDetail(val invoice: InvoiceEntry) : WalletSheet
    data class PaymentDetail(val pay: PayListEntry) : WalletSheet
    data class ActivePaymentDetail(val paymentId: Long) : WalletSheet
    data class ChannelDetail(val channel: PeerChannel) : WalletSheet
    data object CheckRoute : WalletSheet                    // askrene getroutes dry-run
    data object NodeInfoSheet : WalletSheet
    data object Seed : WalletSheet
    data object DropWallet : WalletSheet
}
