@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.light.wallet.ui.transactions

import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ExpandMore
import androidx.compose.material.icons.filled.ExpandLess
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.light.wallet.core.AssetInfo
import app.light.wallet.core.AssetWalletTx
import app.light.wallet.core.OnchainOutput
import app.light.wallet.core.WalletTx
import app.light.wallet.data.ConnState
import app.light.wallet.data.WalletRepository
import app.light.wallet.ui.DisplayUnit
import app.light.wallet.ui.PullRefreshBox
import app.light.wallet.ui.assetPrecisionOf
import app.light.wallet.ui.assetSymbolOf
import app.light.wallet.ui.formatAmount
import app.light.wallet.ui.formatAssetUnits
import app.light.wallet.ui.noRipple
import app.light.wallet.ui.theme.MonoFont
import app.light.wallet.ui.theme.SpaceGrotesk
import app.light.wallet.ui.theme.Tokens
import kotlinx.coroutines.launch

/** Blockstream's Liquid Esplora explorer - mainnet/testnet path differ, no public regtest instance exists. */
internal fun openExplorer(context: Context, network: String, path: String) {
    val base = if (network == "liquid") "https://blockstream.info/liquid" else "https://blockstream.info/liquidtestnet"
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("$base/$path")))
    }
}

internal fun txApproxTime(blockheight: UInt, tip: UInt): String {
    if (blockheight == 0u) return "in mempool"
    if (tip == 0u || tip < blockheight) return "block $blockheight"
    val ts = (System.currentTimeMillis() / 1000).toULong() - ((tip - blockheight).toULong() * 600uL)
    return app.light.wallet.ui.relativeTime(ts)
}

internal fun utxoStatusColor(status: String): Color = when (status) {
    "confirmed" -> Tokens.Green
    "unconfirmed" -> Tokens.Orange
    "immature" -> Tokens.Purple
    else -> Tokens.Faint // spent
}

@Composable
fun TransactionsTab(
    repository: WalletRepository,
    expandRequest: Pair<Int, String?> = 0 to null,
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val txs by repository.transactions.collectAsState()
    val assetTxs by repository.assetTransactions.collectAsState()
    val assets by repository.assets.collectAsState()
    val funds by repository.funds.collectAsState()
    val connState by repository.connState.collectAsState()
    var expandedTx by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(expandRequest) {
        expandRequest.second?.let { expandedTx = it }
    }
    var refreshing by remember { mutableStateOf(false) }
    val unit = DisplayUnit.from(repository.displayUnit)

    // First entry only: fetch if we never loaded; afterwards manual/pull only.
    LaunchedEffect(connState) {
        // Gate on Connected, not just `node != null` - see MainTab's
        // identical comment on why.
        if (connState is ConnState.Connected && repository.transactions.value == null) {
            repository.refreshTransactions(); repository.refreshFunds()
        }
    }

    // Outpoint -> tracked output, for matching tx inputs/outputs.
    val tracked = remember(funds) {
        funds?.outputs?.associateBy { it.txid to it.output } ?: emptyMap()
    }
    val tip = (connState as? ConnState.Connected)?.info?.blockheight ?: 0u

    // One entry per txid, from CLN's wallet, the asset wallet, or both
    // (e.g. a top-up: L-BTC leaves one and lands in the other). Newest
    // first: unconfirmed on top, then descending height.
    val entries = remember(txs, assetTxs) {
        val byId = LinkedHashMap<String, TxEntry>()
        txs.orEmpty().forEach { byId[it.txid] = TxEntry(it.txid, it.blockheight, it, null) }
        assetTxs.orEmpty().forEach { a ->
            val e = byId[a.txid]
            byId[a.txid] = e?.copy(asset = a, blockheight = maxOf(e.blockheight, a.blockheight))
                ?: TxEntry(a.txid, a.blockheight, null, a)
        }
        byId.values.toList().asReversed().sortedByDescending { if (it.blockheight == 0u) UInt.MAX_VALUE else it.blockheight }
    }

    PullRefreshBox(
        refreshing = refreshing,
        onRefresh = {
            refreshing = true
            uiScope.launch {
                val t = repository.refreshTransactions()
                repository.refreshFunds().join(); t.join()
                refreshing = false
            }
        },
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(horizontal = 18.dp)) {
            Text("Transactions", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(top = 6.dp, bottom = 4.dp))
            Text(
                "On-chain wallet · ${repository.network}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                color = Tokens.Faint,
                modifier = Modifier.padding(bottom = 14.dp),
            )
            LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                items(entries, key = { it.txid }) { tx ->
                    TxCard(
                        tx = tx,
                        assets = assets,
                        tracked = tracked,
                        tip = tip,
                        unit = unit,
                        expanded = expandedTx == tx.txid,
                        onToggle = { expandedTx = if (expandedTx == tx.txid) null else tx.txid },
                        onOpen = { path -> openExplorer(context, repository.network, path) },
                    )
                }
                if (entries.isEmpty()) {
                    item {
                        Text(
                            if (txs == null) "Loading…" else "No on-chain transactions yet.",
                            style = MaterialTheme.typography.bodySmall, color = Tokens.Faint,
                            modifier = Modifier.padding(top = 24.dp),
                        )
                    }
                }
            }
        }
    }
}

/** One amount line of a list card; [transfer] = moved between own wallets. */
private data class TxLine(val asset: String, val msat: Long, val transfer: Boolean = false)

/** One on-chain transaction as either wallet (or both) sees it. */
private data class TxEntry(
    val txid: String,
    /** 0 = unconfirmed. */
    val blockheight: UInt,
    /** CLN's wallet (L-BTC) side, if it took part. */
    val cln: WalletTx?,
    /** The asset wallet's side, if it took part: net change per asset. */
    val asset: AssetWalletTx?,
)

/** "+1.5 WDODGE" / "−1 234" (L-BTC in the display unit, no label - as before). */
private fun signedAmount(msat: Long, assetId: String, assets: List<AssetInfo>?, unit: DisplayUnit): String {
    val sign = if (msat >= 0) "+" else "−"
    val abs = kotlin.math.abs(msat).toULong()
    return if (assetId == "lbtc") sign + formatAmount(abs, unit)
    else sign + formatAssetUnits(abs, assetPrecisionOf(assetId, assets)) + " " + assetSymbolOf(assetId, assets)
}

@Composable
private fun TxCard(
    tx: TxEntry,
    assets: List<AssetInfo>?,
    tracked: Map<Pair<String, UInt>, OnchainOutput>,
    tip: UInt,
    unit: DisplayUnit,
    expanded: Boolean,
    onToggle: () -> Unit,
    onOpen: (String) -> Unit,
) {
    val ins = tx.cln?.inputs.orEmpty().mapNotNull { i -> tracked[i.txid to i.index]?.let { i to it } }
    val outs = tx.cln?.outputs.orEmpty().mapNotNull { o -> tracked[tx.txid to o.index]?.let { o to it } }
    val assetAmounts = tx.asset?.amounts.orEmpty()
    // CLN's side: the fee output only counts as ours if we funded the tx.
    val clnFeeMsat = if (ins.isNotEmpty()) (tx.cln?.feeMsat ?: 0uL).toLong() else 0L
    val clnNetMsat = outs.sumOf { it.second.amountMsat.toLong() } - ins.sumOf { it.second.amountMsat.toLong() } + clnFeeMsat
    val feeMsat = clnFeeMsat + (tx.asset?.feeMsat ?: 0uL).toLong()
    // The list: issued assets moved, plus the main wallet's L-BTC - the fee
    // and the Elements wallet's L-BTC (e.g. a top-up landing there) only
    // show in the details. With neither, the Elements wallet's L-BTC.
    val elementsLbtcMsat = assetAmounts.filter { it.asset == "lbtc" }.sumOf { it.amountMsat }
    val assetLines = assetAmounts.filter { it.asset != "lbtc" && it.amountMsat != 0L }.map { TxLine(it.asset, it.amountMsat) }
    // Main wallet -> Elements wallet (a fee top-up): exactly what left one
    // landed in the other - a transfer between own wallets, not a spend.
    val topUp = tx.cln != null && clnNetMsat < 0L && elementsLbtcMsat == -clnNetMsat
    val lines = when {
        topUp -> assetLines + TxLine("lbtc", elementsLbtcMsat, transfer = true)
        tx.cln != null && (clnNetMsat != 0L || assetLines.isEmpty()) -> assetLines + TxLine("lbtc", clnNetMsat)
        assetLines.isNotEmpty() -> assetLines
        else -> listOf(TxLine("lbtc", elementsLbtcMsat))
    }.let { ls ->
        if (ls.any { it.msat != 0L }) ls
        else {
            // Nothing net, fee aside: a transfer within own wallets (e.g.
            // a self-send that only regrouped outputs). Show what moved.
            val clnMoved = if (ins.isNotEmpty()) outs.sumOf { it.second.amountMsat.toLong() } else 0L
            val elementsMoved = tx.asset?.outputs.orEmpty().filter { it.amountMsat > 0L }
                .groupBy { it.asset }.mapValues { (_, xs) -> xs.sumOf { it.amountMsat } }
            val moved = (elementsMoved + ("lbtc" to (elementsMoved["lbtc"] ?: 0L) + clnMoved))
                .filterValues { it != 0L }
                .map { (a, m) -> TxLine(a, m, transfer = true) }
            moved.ifEmpty { ls }
        }
    }
    val confirmed = tx.blockheight > 0u
    val conf = if (confirmed && tip >= tx.blockheight) (tip - tx.blockheight + 1u) else 0u

    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Card, RoundedCornerShape(14.dp))
            .border(
                1.dp,
                if (expanded) Tokens.Accent.copy(alpha = .3f) else Tokens.BorderSoft,
                RoundedCornerShape(14.dp),
            )
            .padding(horizontal = 14.dp, vertical = 13.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().noRipple(onClick = onToggle),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    "${tx.txid.take(6)}…${tx.txid.takeLast(5)}",
                    style = MaterialTheme.typography.bodyMedium.copy(fontSize = 14.5.sp, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold),
                )
                Text(
                    txApproxTime(tx.blockheight, tip),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                    color = Tokens.Dim,
                )
            }
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                lines.forEach { l ->
                    // A transfer between own wallets: no sign, and neither
                    // the green of income nor the white of a spend.
                    val text = signedAmount(l.msat, l.asset, assets, unit).let { if (l.transfer) it.drop(1) else it }
                    Text(
                        text + if (l.asset == "lbtc") " ${unit.label}" else "",
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 15.sp),
                        color = when {
                            l.transfer -> Tokens.Dim
                            l.msat >= 0 -> Tokens.Green
                            else -> Tokens.Text
                        },
                    )
                }
                Text(
                    if (confirmed) "Confirmed" else "Unconfirmed",
                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.5.sp),
                    color = if (confirmed) Tokens.Green else Tokens.Orange,
                )
            }
            Icon(
                if (expanded) Icons.Filled.ExpandLess else Icons.Filled.ExpandMore,
                null, tint = Tokens.Faint, modifier = Modifier.size(14.dp),
            )
        }

        if (expanded) {
            Column(
                modifier = Modifier.padding(top = 11.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Box(Modifier.fillMaxWidth().size(1.dp).background(Color(0x0DFFFFFF)))
                // TXID row -> mempool
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.CardDeep, RoundedCornerShape(11.dp))
                        .border(1.dp, Tokens.Border, RoundedCornerShape(11.dp))
                        .noRipple { onOpen("tx/${tx.txid}") }
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text("TXID", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                    Text(
                        tx.txid,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFamily = MonoFont),
                        color = Tokens.TextMid, maxLines = 1,
                        modifier = Modifier.weight(1f),
                    )
                    Icon(Icons.AutoMirrored.Filled.OpenInNew, null, tint = Tokens.Accent, modifier = Modifier.size(11.dp))
                }
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.CardDeep, RoundedCornerShape(11.dp))
                        .border(1.dp, Tokens.Border, RoundedCornerShape(11.dp))
                        .padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        "Confirmations",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                        color = Tokens.Faint,
                    )
                    Text(
                        if (confirmed) "$conf" else "Unconfirmed",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFamily = MonoFont),
                        color = Tokens.TextMid,
                        textAlign = androidx.compose.ui.text.style.TextAlign.End,
                        modifier = Modifier.weight(1f),
                    )
                }
                if (feeMsat > 0L) {
                    WalletAmountRow("Fee", formatAmount(feeMsat.toULong(), unit) + " ${unit.label}", null)
                }
                // The Elements (asset) wallet's side: what moved to/from
                // which address - e.g. where a top-up landed.
                val elements = tx.asset?.outputs.orEmpty()
                if (elements.isNotEmpty()) {
                    Text("Elements wallet", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                    elements.forEach { o ->
                        ElementsRow(
                            address = o.address,
                            amount = signedAmount(o.amountMsat, o.asset, assets, unit) + if (o.asset == "lbtc") " ${unit.label}" else "",
                        ) { if (o.address.isNotBlank()) onOpen("address/${o.address}") }
                    }
                }
                Text(
                    "Only inputs and outputs tracked by your CLN node are shown below.",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp, lineHeight = 18.sp),
                    color = Tokens.Dim,
                )
                if (ins.isNotEmpty()) {
                    Text("Inputs", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                    ins.forEach { (inp, utxo) ->
                        IoRow(vout = inp.index, utxo = utxo, unit = unit) {
                            onOpen(utxo.address?.let { a -> "address/$a" } ?: "tx/${inp.txid}")
                        }
                    }
                }
                if (outs.isNotEmpty()) {
                    Text("Outputs", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                    outs.forEach { (out, utxo) ->
                        IoRow(vout = out.index, utxo = utxo, unit = unit) {
                            onOpen(utxo.address?.let { a -> "address/$a" } ?: "tx/${tx.txid}")
                        }
                    }
                }
                if (ins.isEmpty() && outs.isEmpty()) {
                    Text(
                        "No tracked inputs or outputs in this transaction.",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = Tokens.Faint,
                    )
                }
            }
        }
    }
}

/** A label/amount row in the details; [msat] (null = neutral) colours the amount like the list does. */
@Composable
private fun WalletAmountRow(label: String, amount: String, msat: Long?) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.CardDeep, RoundedCornerShape(11.dp))
            .border(1.dp, Tokens.Border, RoundedCornerShape(11.dp))
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            label,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
            color = Tokens.TextMid, modifier = Modifier.weight(1f),
        )
        Text(
            amount,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
            color = if (msat != null && msat >= 0) Tokens.Green else Tokens.Text,
        )
    }
}

@Composable
private fun ElementsRow(address: String, amount: String, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.CardDeep, RoundedCornerShape(11.dp))
            .border(1.dp, Tokens.Border, RoundedCornerShape(11.dp))
            .noRipple(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            address.ifBlank { "—" },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFamily = MonoFont),
            color = Tokens.TextMid, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Text(
            amount,
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
            color = Tokens.Text,
        )
    }
}

@Composable
private fun IoRow(vout: UInt, utxo: OnchainOutput, unit: DisplayUnit, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.CardDeep, RoundedCornerShape(11.dp))
            .border(1.dp, Tokens.Border, RoundedCornerShape(11.dp))
            .noRipple(onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Text(
            "#$vout",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontFamily = MonoFont, fontWeight = FontWeight.SemiBold),
            color = Tokens.Faint,
        )
        Text(
            utxo.address ?: "${utxo.txid.take(8)}…",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFamily = MonoFont),
            color = Tokens.TextMid, maxLines = 1,
            modifier = Modifier.weight(1f),
        )
        Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(
                formatAmount(utxo.amountMsat, unit) + " ${unit.label}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
                color = Tokens.Text,
            )
            Text(
                utxo.status.replaceFirstChar { it.uppercase() },
                style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp),
                color = utxoStatusColor(utxo.status),
            )
        }
    }
}
