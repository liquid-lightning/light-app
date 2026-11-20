package app.light.wallet.ui.sheets

import app.light.wallet.core.InvoiceEntry
import app.light.wallet.core.PayListEntry
import app.light.wallet.core.PeerChannel

/** Every bottom sheet in the app, mirroring the design's sheet layer. */
sealed interface WalletSheet {
    /**
     * On-chain address + QR. L-BTC ([assetId] null) goes to CLN's wallet
     * (newaddr); an issued asset to the node's asset wallet - CLN's own
     * wallet ignores every non-L-BTC output.
     */
    data class Receive(val assetSymbol: String? = null, val assetId: String? = null) : WalletSheet
    data object SendChain : WalletSheet                     // on-chain withdraw
    /** On-chain send of an issued asset from the asset wallet. */
    data class SendAsset(val assetId: String, val symbol: String) : WalletSheet
    /** The testnet faucet for an asset ([assetId] null = L-BTC). */
    data class Faucet(val assetId: String?, val symbol: String) : WalletSheet
    /** Move L-BTC from CLN's wallet into the asset wallet, to pay asset fees. */
    data object TopUpAssetWallet : WalletSheet
    /** Invoice creation; [assetId] preselects an issued asset (null = L-BTC). */
    data class NewInvoice(val assetId: String? = null) : WalletSheet
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
    /** [assetId] preselects an asset channel funded from the asset wallet (null = L-BTC). */
    data class OpenChannel(val assetId: String? = null) : WalletSheet
    data class InvoiceDetail(val invoice: InvoiceEntry) : WalletSheet
    data class PaymentDetail(val pay: PayListEntry) : WalletSheet
    data class ActivePaymentDetail(val paymentId: Long) : WalletSheet
    data class ChannelDetail(val channel: PeerChannel) : WalletSheet
    data object CheckRoute : WalletSheet                    // askrene getroutes dry-run
    data object NodeInfoSheet : WalletSheet
    data object Seed : WalletSheet
    data object DropWallet : WalletSheet
}
