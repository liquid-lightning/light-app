@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package app.light.wallet.ui.home

import androidx.compose.animation.core.animateFloat

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowDownward
import androidx.compose.material.icons.filled.ArrowUpward
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.ChevronLeft
import androidx.compose.material.icons.filled.ChevronRight
import androidx.compose.material.icons.filled.WaterDrop
import androidx.compose.material.icons.outlined.ContentCopy
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
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.animation.togetherWith
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.light.wallet.core.AssetBalance
import app.light.wallet.core.AssetInfo
import app.light.wallet.data.ConnState
import app.light.wallet.data.WalletRepository
import app.light.wallet.ui.DisplayUnit
import app.light.wallet.ui.LiquidityBar
import app.light.wallet.ui.PullRefreshBox
import app.light.wallet.ui.RowIcon
import app.light.wallet.ui.SoftPillButton
import app.light.wallet.ui.autoRefreshTick
import app.light.wallet.ui.copyToClipboard
import app.light.wallet.ui.DEFAULT_ASSET_PRECISION
import app.light.wallet.ui.formatAmount
import app.light.wallet.ui.formatAmountIn
import app.light.wallet.ui.formatAssetUnits
import app.light.wallet.ui.formatCompactSat
import app.light.wallet.ui.noRipple
import app.light.wallet.ui.relativeTime
import app.light.wallet.ui.satsToBtcLine
import app.light.wallet.ui.sheets.WalletSheet
import app.light.wallet.ui.shortId
import app.light.wallet.ui.theme.MonoFont
import app.light.wallet.ui.theme.SpaceGrotesk
import app.light.wallet.ui.theme.Tokens
import kotlinx.coroutines.launch

private data class RecentItem(
    val label: String, val time: String, val amount: String,
    val amountColor: Color, val iconColor: Color, val chain: Boolean, val sort: ULong,
    val onClick: () -> Unit,
)

@Composable
fun MainTab(
    repository: WalletRepository,
    onOpenSheet: (WalletSheet) -> Unit,
    onRecentInvoice: (app.light.wallet.core.InvoiceEntry) -> Unit = {},
    onRecentPay: (app.light.wallet.core.PayListEntry) -> Unit = {},
    onRecentTx: (String) -> Unit = {},
) {
    val context = LocalContext.current
    val uiScope = rememberCoroutineScope()
    val connState by repository.connState.collectAsState()
    val funds by repository.funds.collectAsState()
    val invoices by repository.invoices.collectAsState()
    val pays by repository.pays.collectAsState()
    val transactions by repository.transactions.collectAsState()
    val assets by repository.assets.collectAsState()
    val assetBalances by repository.assetBalances.collectAsState()
    val channels by repository.channels.collectAsState()
    val assetWalletLbtc by repository.assetWalletLbtcMsat.collectAsState()
    var refreshing by remember { mutableStateOf(false) }
    val tick = autoRefreshTick()
    val unit = DisplayUnit.from(repository.displayUnit)

    // The Main screen is the only one that auto-refreshes (every 30s while
    // visible); everything else waits for pull-to-refresh or an action.
    LaunchedEffect(tick, connState) {
        // Gate on Connected, not just `node != null` - the node handle
        // exists as soon as connectNode() returns, well before the pod is
        // actually ready, and an RPC fired during that window just spams
        // ember-server's logs with "connecting to node: transport error"
        // for no benefit (connect() retries on its own once ready).
        if (connState is ConnState.Connected) repository.refreshMain()
    }

    // Recent activity: paid invoices + lightning payments + on-chain
    // transactions, all derived from the shared flows.
    val recent = remember(funds, invoices, pays, transactions, connState, unit, assets) {
        val now = (System.currentTimeMillis() / 1000).toULong()
        val tip = (connState as? ConnState.Connected)?.info?.blockheight ?: 0u
        val inv = invoices.orEmpty().filter { it.status == "paid" }.map { i ->
            RecentItem(
                label = i.description?.ifBlank { null } ?: i.label,
                time = relativeTime(i.paidAt),
                amount = "+" + formatAmountIn(i.amountReceivedMsat, i.asset, assets, unit),
                amountColor = Tokens.Green, iconColor = Tokens.Green,
                chain = false, sort = i.paidAt ?: 0uL,
                onClick = { onRecentInvoice(i) },
            )
        }
        val sent = pays.orEmpty().map { p ->
            RecentItem(
                label = p.description?.ifBlank { null } ?: shortId(p.destination, 8),
                time = relativeTime(p.completedAt ?: p.createdAt),
                amount = "−" + formatAmount(p.amountMsat, unit),
                amountColor = Tokens.Text, iconColor = Tokens.Red,
                chain = false, sort = p.completedAt ?: p.createdAt ?: 0uL,
                onClick = { onRecentPay(p) },
            )
        }
        // On-chain: whole transactions, valued as the net of the inputs and
        // outputs our node tracks (deposits positive, withdrawals negative).
        val tracked = funds?.outputs.orEmpty().associateBy { it.txid to it.output }
        val chain = transactions.orEmpty().mapNotNull { tx ->
            val inSum = tx.inputs.sumOf { i -> (tracked[i.txid to i.index]?.amountMsat ?: 0uL).toLong() }
            val outSum = tx.outputs.sumOf { o -> (tracked[tx.txid to o.index]?.amountMsat ?: 0uL).toLong() }
            val net = outSum - inSum
            if (inSum == 0L && outSum == 0L) return@mapNotNull null
            val confirmed = tx.blockheight > 0u
            val ts = if (!confirmed || tip == 0u || tip < tx.blockheight) now
            else now - ((tip - tx.blockheight).toULong() * 600uL)
            RecentItem(
                label = if (net >= 0) "Received L-BTC" else "Sent L-BTC",
                time = if (!confirmed) "confirming…" else relativeTime(ts),
                amount = (if (net >= 0) "+" else "−") + formatAmount(kotlin.math.abs(net).toULong(), unit),
                amountColor = if (net >= 0) Tokens.Green else Tokens.Text,
                iconColor = Tokens.Liquid,
                chain = true, sort = ts,
                onClick = { onRecentTx(tx.txid) },
            )
        }
        (inv + sent + chain).sortedByDescending { it.sort }.take(4)
    }

    val f = funds
    PullRefreshBox(
        refreshing = refreshing,
        onRefresh = {
            refreshing = true
            uiScope.launch {
                repository.refreshFunds().join()
                repository.refreshInvoices().join()
                repository.refreshPays().join()
                refreshing = false
            }
        },
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp),
        ) {
            // ── Header ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 6.dp, bottom = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                androidx.compose.foundation.Image(
                    painter = androidx.compose.ui.res.painterResource(app.light.wallet.R.drawable.logo_lightapp_small),
                    contentDescription = null,
                    modifier = Modifier
                        .size(34.dp)
                        .clip(RoundedCornerShape(9.dp)),
                )
                Text(
                    "LiqhtApp",
                    style = MaterialTheme.typography.titleMedium.copy(fontSize = 17.sp),
                    modifier = Modifier.weight(1f),
                )
                val (chipText, chipColor) = when (val s = connState) {
                    is ConnState.Connected -> "Node running" to Tokens.Green
                    is ConnState.Connecting -> "Connecting…" to Tokens.Orange
                    is ConnState.Provisioning -> when (s.phase) {
                        app.light.wallet.core.NodePhase.STOPPED -> "Waking node…" to Tokens.Orange
                        else -> "Setting up node…" to Tokens.Orange
                    }
                    is ConnState.Failed -> "Offline" to Tokens.Red
                    else -> "Idle" to Tokens.Dim
                }
                Row(
                    modifier = Modifier
                        .background(chipColor.copy(alpha = .08f), RoundedCornerShape(999.dp))
                        .border(1.dp, chipColor.copy(alpha = .25f), RoundedCornerShape(999.dp))
                        .noRipple { if (connState is ConnState.Failed) repository.connectInBackground(force = true) }
                        .padding(horizontal = 11.dp, vertical = 5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                ) {
                    // Pulsing dot while the node is still coming up.
                    val busy = connState is ConnState.Connecting || connState is ConnState.Provisioning
                    val dotAlpha = if (busy) {
                        val t = androidx.compose.animation.core.rememberInfiniteTransition(label = "chipDot")
                        val a by t.animateFloat(
                            initialValue = 1f, targetValue = .2f,
                            animationSpec = androidx.compose.animation.core.infiniteRepeatable(
                                androidx.compose.animation.core.tween(700),
                                androidx.compose.animation.core.RepeatMode.Reverse,
                            ),
                            label = "chipDotAlpha",
                        )
                        a
                    } else 1f
                    Box(Modifier.size(6.dp).background(chipColor.copy(alpha = dotAlpha), CircleShape))
                    Text(chipText, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold), color = chipColor)
                }
            }

            // ── Asset pager: L-BTC first, then every issued asset ──
            // Each page has the same layout (on-chain + Lightning cards).
            // Swipe or tap the arrows to switch.
            val assetsById = remember(assets) { assets.orEmpty().associateBy(AssetInfo::id) }
            val pages = remember(assets, assetBalances) {
                val balById = assetBalances.orEmpty().associateBy(AssetBalance::asset)
                val known = assets.orEmpty().filter { it.id != "lbtc" }.map { it.id }
                val unknown = balById.keys.filter { it != "lbtc" && it !in known }
                listOf<String?>(null) + (known + unknown).map { it }
            }
            val pagerState = androidx.compose.foundation.pager.rememberPagerState(pageCount = { pages.size })
            val current = pages.getOrNull(pagerState.currentPage)
            PagerHeader(
                title = current?.let { id -> assetsById[id]?.symbol ?: shortId(id, 6) } ?: "L-BTC",
                subtitle = current?.let { id -> assetsById[id]?.name } ?: "Liquid Bitcoin",
                page = pagerState.currentPage,
                pageCount = pages.size,
                onPrev = { uiScope.launch { pagerState.animateScrollToPage(pagerState.currentPage - 1) } },
                onNext = { uiScope.launch { pagerState.animateScrollToPage(pagerState.currentPage + 1) } },
            )
            androidx.compose.foundation.pager.HorizontalPager(
                state = pagerState,
                pageSpacing = 14.dp,
                verticalAlignment = Alignment.Top,
                key = { pages.getOrNull(it) ?: "lbtc" },
                modifier = Modifier.fillMaxWidth(),
            ) { index ->
                val assetId = pages.getOrNull(index)
                val nodeId = (connState as? ConnState.Connected)?.info?.id
                if (assetId == null) {
                    // L-BTC channels only: listfunds' outbound total also
                    // sums asset channels (their msat are another asset's).
                    val lbtcChannels = channels?.filter { it.state == "CHANNELD_NORMAL" && it.asset == null }
                    Column {
                        OnchainCard(
                            title = "Liquid · on-chain",
                            badge = { Icon(Icons.Filled.WaterDrop, null, tint = Color.White, modifier = Modifier.size(12.dp)) },
                            badgeColor = Tokens.Liquid,
                            // Confirmed, unreserved only: a pending send's inputs and
                            // its unconfirmed change don't count until it's mined.
                            amount = formatAmount(f?.onchainConfirmedMsat, unit),
                            unitLabel = unit.label,
                            worth = null,
                            onReceive = { onOpenSheet(WalletSheet.Receive()) },
                            onSend = { onOpenSheet(WalletSheet.SendChain) },
                            onFaucet = { onOpenSheet(WalletSheet.Faucet(null, "L-BTC")) },
                        )
                        LightningCard(
                            title = "Lightning",
                            channels = lbtcChannels?.size ?: f?.channels?.size ?: 0,
                            amount = formatAmount(lbtcChannels?.sumOf { (it.toUsMsat ?: 0uL).toLong() }?.toULong() ?: f?.outboundMsat, unit),
                            unitLabel = unit.label,
                            worth = null,
                            nodeId = nodeId,
                            onCopyNodeId = { nodeId?.let { copyToClipboard(context, "Node ID", it) } },
                            onCreateInvoice = { onOpenSheet(WalletSheet.NewInvoice()) },
                            onPay = { onOpenSheet(WalletSheet.Pay()) },
                        )
                    }
                } else {
                    val info = assetsById[assetId]
                    val balance = assetBalances.orEmpty().firstOrNull { it.asset == assetId }
                    val symbol = info?.symbol ?: shortId(assetId, 6)
                    val precision = info?.precision?.toInt() ?: DEFAULT_ASSET_PRECISION
                    val price = info?.price?.takeIf { it.fromAsset == "lbtc" }
                    // Reference value only: an actual swap adds the pricing
                    // margin and fees on top (see the Pay sheet's own quote).
                    fun worth(msat: ULong): String? = price?.let {
                        val v = (java.math.BigInteger.valueOf(msat.toLong())
                            * java.math.BigInteger.valueOf(it.ratePpm.toLong())
                            / java.math.BigInteger.valueOf(1_000_000L)).toLong().toULong()
                        "≈ ${formatAmount(v, unit)} ${unit.label}"
                    }
                    val onchain = balance?.onchainBalanceMsat ?: 0uL
                    // Summed here from live (CHANNELD_NORMAL) channels, not
                    // taken from assetbalances: older assetswap-plugin builds
                    // also count closed (ONCHAIN) channels, whose money is
                    // already back on-chain.
                    val channel = channels
                        ?.filter { it.state == "CHANNELD_NORMAL" && it.asset.equals(assetId, ignoreCase = true) }
                        ?.sumOf { (it.toUsMsat ?: 0uL).toLong() }?.toULong()
                        ?: balance?.channelBalanceMsat ?: 0uL
                    Column {
                        // Asset amounts are shown in whole units with the
                        // asset's own precision (base_units / 10^precision).
                        OnchainCard(
                            title = "$symbol · on-chain",
                            badge = {
                                Text(
                                    symbol.take(1).uppercase(),
                                    style = MaterialTheme.typography.labelSmall.copy(fontSize = 11.sp, fontWeight = FontWeight.Bold),
                                    color = Color.White,
                                )
                            },
                            badgeColor = Tokens.Green,
                            amount = formatAssetUnits(onchain, precision),
                            unitLabel = symbol,
                            amountColor = Tokens.Green,
                            worth = worth(onchain),
                            priceLine = price?.let {
                                val perUnit = formatLbtcPerAssetUnit(it.ratePpm, precision, unit)
                                val label = if (unit == DisplayUnit.SAT && perUnit == "1") "sat" else unit.label
                                "1 $symbol = $perUnit $label"
                            },
                            // The asset wallet (the node's own elementsd
                            // wallet), not CLN's - see WalletSheet.Receive.
                            onReceive = { onOpenSheet(WalletSheet.Receive(symbol, assetId)) },
                            onSend = { onOpenSheet(WalletSheet.SendAsset(assetId, symbol)) },
                            onFaucet = { onOpenSheet(WalletSheet.Faucet(assetId, symbol)) },
                            assetId = assetId,
                            feeBalance = "${formatAmount(assetWalletLbtc, unit)} ${unit.label}",
                            feeBalanceEmpty = (assetWalletLbtc ?: 0uL) == 0uL,
                            onTopUp = { onOpenSheet(WalletSheet.TopUpAssetWallet) },
                        )
                        LightningCard(
                            title = "Lightning · $symbol",
                            channels = null,
                            amount = formatAssetUnits(channel, precision),
                            unitLabel = symbol,
                            worth = worth(channel),
                            nodeId = nodeId,
                            onCopyNodeId = { nodeId?.let { copyToClipboard(context, "Node ID", it) } },
                            onCreateInvoice = { onOpenSheet(WalletSheet.NewInvoice(assetId)) },
                            onPay = { onOpenSheet(WalletSheet.Pay()) },
                            openChannelLabel = "Open $symbol channel",
                            onOpenChannel = { onOpenSheet(WalletSheet.OpenChannel(assetId)) },
                        )
                    }
                }
            }

            // ── Recent activity ──
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 22.dp, bottom = 10.dp),
                verticalAlignment = Alignment.Bottom,
            ) {
                Text("Recent activity", style = MaterialTheme.typography.titleSmall, modifier = Modifier.weight(1f))
            }
            Column(verticalArrangement = Arrangement.spacedBy(8.dp), modifier = Modifier.padding(bottom = 16.dp)) {
                if (recent.isEmpty()) {
                    Text(
                        "No activity yet — create an invoice or get an on-chain address to receive funds.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.Faint,
                    )
                }
                recent.forEach { item ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(Tokens.Card, RoundedCornerShape(14.dp))
                            .border(1.dp, Tokens.BorderSoft, RoundedCornerShape(14.dp))
                            .noRipple(onClick = item.onClick)
                            .padding(horizontal = 14.dp, vertical = 12.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp),
                    ) {
                        RowIcon(item.iconColor.copy(alpha = .1f)) {
                            Icon(
                                if (item.chain) Icons.Filled.WaterDrop else Icons.Filled.Bolt,
                                null, tint = item.iconColor, modifier = Modifier.size(15.dp),
                            )
                        }
                        Column(modifier = Modifier.weight(1f)) {
                            Text(item.label, style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.Bold), maxLines = 1)
                            Text(item.time, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Tokens.Dim)
                        }
                        Text(
                            item.amount,
                            style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold),
                            color = item.amountColor,
                        )
                    }
                }
            }
        }
    }
}

/** "‹  L-BTC  ›" switcher above the cards, with page dots. */
@Composable
private fun PagerHeader(
    title: String, subtitle: String,
    page: Int, pageCount: Int,
    onPrev: () -> Unit, onNext: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PagerArrow(Icons.Filled.ChevronLeft, "Previous asset", enabled = page > 0, onClick = onPrev)
        Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
            androidx.compose.animation.AnimatedContent(
                targetState = title to subtitle,
                transitionSpec = {
                    androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(220)) togetherWith
                        androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(120))
                },
                label = "pagerTitle",
            ) { (t, sub) ->
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(t, style = MaterialTheme.typography.titleSmall.copy(fontSize = 15.sp), maxLines = 1)
                    Text(
                        sub,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                        color = Tokens.Dim, maxLines = 1,
                        overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    )
                }
            }
            if (pageCount > 1) {
                Row(
                    modifier = Modifier.padding(top = 6.dp),
                    horizontalArrangement = Arrangement.spacedBy(5.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    repeat(pageCount) { i ->
                        val on = i == page
                        val w by androidx.compose.animation.core.animateDpAsState(if (on) 16.dp else 6.dp, label = "dotW")
                        val c by androidx.compose.animation.animateColorAsState(
                            if (on) Tokens.Accent else Tokens.Faint.copy(alpha = .5f), label = "dotC",
                        )
                        Box(Modifier.height(6.dp).width(w).background(c, RoundedCornerShape(999.dp)))
                    }
                }
            }
        }
        PagerArrow(Icons.Filled.ChevronRight, "Next asset", enabled = page < pageCount - 1, onClick = onNext)
    }
}

@Composable
private fun PagerArrow(
    icon: androidx.compose.ui.graphics.vector.ImageVector,
    description: String,
    enabled: Boolean,
    onClick: () -> Unit,
) {
    val alpha by androidx.compose.animation.core.animateFloatAsState(if (enabled) 1f else .3f, label = "arrowAlpha")
    Box(
        modifier = Modifier
            .size(38.dp)
            .alpha(alpha)
            .background(Color(0x0FFFFFFF), CircleShape)
            .border(1.dp, Tokens.BorderSoft, CircleShape)
            .noRipple(enabled = enabled, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = if (enabled) Tokens.Accent else Tokens.Dim, modifier = Modifier.size(20.dp))
    }
}

@Composable
private fun OnchainCard(
    title: String,
    badge: @Composable () -> Unit,
    badgeColor: Color,
    amount: String,
    unitLabel: String,
    worth: String?,
    onReceive: () -> Unit,
    onSend: (() -> Unit)?,
    /** Opens this asset's testnet faucet. */
    onFaucet: () -> Unit,
    amountColor: Color = Tokens.Text,
    priceLine: String? = null,
    assetId: String? = null,
    feeBalance: String? = null,
    feeBalanceEmpty: Boolean = false,
    onTopUp: () -> Unit = {},
) {
    val context = LocalContext.current
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF141B23), Color(0xFF10161C))),
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, Tokens.Border, RoundedCornerShape(20.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(Modifier.size(22.dp).background(badgeColor, CircleShape), contentAlignment = Alignment.Center) { badge() }
            Text(title, style = MaterialTheme.typography.labelMedium, color = Tokens.Label, modifier = Modifier.weight(1f))
            priceLine?.let {
                Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Tokens.Dim)
            }
        }
        Box(Modifier.padding(top = 14.dp)) {
            app.light.wallet.ui.BalanceText(amount = amount, unit = unitLabel, color = amountColor)
        }
        worth?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Tokens.Dim, modifier = Modifier.padding(top = 4.dp))
        }
        // Issued assets: the id, shortened to its start…end; tap copies it whole.
        assetId?.let { id ->
            Box(
                Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp)
                    .height(1.dp)
                    .background(Tokens.Border),
            )
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 12.dp)
                    .noRipple { copyToClipboard(context, "Asset ID", id) },
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Asset ID",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                    color = Tokens.Dim,
                )
                Text(
                    shortId(id, 8),
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = MonoFont),
                    color = Tokens.TextMid,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Icon(Icons.Outlined.ContentCopy, null, tint = Tokens.Accent, modifier = Modifier.size(12.dp))
            }
        }
        // The asset wallet's L-BTC: pays every asset send's on-chain fee.
        feeBalance?.let { fee ->
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                Text(
                    "Fee balance",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                    color = Tokens.Dim,
                )
                Text(
                    fee,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = MonoFont),
                    color = if (feeBalanceEmpty) Tokens.Orange else Tokens.TextMid,
                    textAlign = androidx.compose.ui.text.style.TextAlign.End,
                    modifier = Modifier.weight(1f),
                )
                Text(
                    "Top up",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                    color = Tokens.Accent,
                    modifier = Modifier.noRipple(onClick = onTopUp),
                )
            }
        }
        Row(modifier = Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            SoftPillButton("Receive", modifier = Modifier.weight(1f), icon = Icons.Filled.ArrowDownward, onClick = onReceive)
            if (onSend != null) {
                SoftPillButton("Send", modifier = Modifier.weight(1f), icon = Icons.Filled.ArrowUpward, onClick = onSend)
            }
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .background(Color(0x0FFFFFFF), CircleShape)
                    .noRipple(onClick = onFaucet),
                contentAlignment = Alignment.Center,
            ) {
                Icon(app.light.wallet.ui.FaucetIcon, "Faucet", tint = Tokens.TextMid, modifier = Modifier.size(18.dp))
            }
        }
    }
}

@Composable
private fun LightningCard(
    title: String,
    channels: Int?,
    amount: String,
    unitLabel: String,
    worth: String?,
    nodeId: String?,
    onCopyNodeId: () -> Unit,
    onCreateInvoice: () -> Unit,
    onPay: () -> Unit,
    openChannelLabel: String? = null,
    onOpenChannel: () -> Unit = {},
) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(
                Brush.linearGradient(listOf(Color(0xFF26200F), Color(0xFF181307))),
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, Tokens.Accent.copy(alpha = .2f), RoundedCornerShape(20.dp))
            .padding(20.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Box(
                Modifier.size(22.dp).background(Tokens.Accent.copy(alpha = .15f), CircleShape),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Filled.Bolt, null, tint = Tokens.Accent, modifier = Modifier.size(13.dp)) }
            Text(title, style = MaterialTheme.typography.labelMedium, color = Tokens.Label)
            Spacer(Modifier.weight(1f))
            channels?.let {
                Text("$it channels", style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Tokens.Dim)
            }
        }
        Box(Modifier.padding(top = 14.dp)) {
            app.light.wallet.ui.BalanceText(amount = amount, unit = unitLabel, color = Tokens.Accent)
        }
        worth?.let {
            Text(it, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp), color = Tokens.Dim, modifier = Modifier.padding(top = 4.dp))
        }
        openChannelLabel?.let {
            Text(
                it,
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                color = Tokens.Accent,
                modifier = Modifier.padding(top = 8.dp).noRipple(onClick = onOpenChannel),
            )
        }
        Box(
            Modifier
                .fillMaxWidth()
                .padding(top = 14.dp)
                .height(1.dp)
                .background(Tokens.Border),
        )
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .noRipple(enabled = nodeId != null, onClick = onCopyNodeId),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                "Node ID",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                color = Tokens.Dim,
            )
            Text(
                shortId(nodeId, 12),
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = MonoFont),
                color = Tokens.TextMid,
                maxLines = 1,
                overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                textAlign = androidx.compose.ui.text.style.TextAlign.End,
                modifier = Modifier.weight(1f),
            )
            if (nodeId != null) Icon(Icons.Outlined.ContentCopy, null, tint = Tokens.Accent, modifier = Modifier.size(12.dp))
        }
        Row(modifier = Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .height(44.dp)
                    .background(Tokens.Accent, RoundedCornerShape(999.dp))
                    .noRipple(onClick = onCreateInvoice),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "Create invoice",
                    style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.5.sp),
                    color = Tokens.AccentInk,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.padding(horizontal = 6.dp),
                )
            }
            SoftPillButton("Pay invoice", modifier = Modifier.weight(1f), onClick = onPay)
        }
    }
}

/**
 * L-BTC cost of 1 whole asset (10^precision base units of 1 000 msat each,
 * the same unit the balance itself is shown in) at `ratePpm`, in `unit`:
 * that's ratePpm * 10^precision / 1e6 sats, kept exact so a sub-sat price
 * still shows.
 */
private fun formatLbtcPerAssetUnit(ratePpm: ULong, precision: Int, unit: DisplayUnit): String {
    val sats = java.math.BigDecimal(ratePpm.toLong()).movePointLeft(6 - precision)
    val v = when (unit) {
        DisplayUnit.SAT -> sats
        DisplayUnit.MSAT -> sats.movePointRight(3)
        DisplayUnit.BTC -> sats.movePointLeft(8)
    }.stripTrailingZeros()
    return if (v.scale() <= 0) {
        String.format(java.util.Locale.US, "%,d", v.toBigInteger()).replace(',', ' ')
    } else v.toPlainString()
}

