package app.light.wallet.ui

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.core.tween
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.ui.draw.clip
import androidx.compose.animation.core.animateFloat
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.filled.Circle
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Hub
import androidx.compose.material.icons.outlined.ReceiptLong
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.ViewInAr
import androidx.compose.material.icons.outlined.SwapHoriz
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.light.wallet.data.ActivePaymentStatus
import app.light.wallet.data.ConnState
import app.light.wallet.data.WalletRepository
import app.light.wallet.ui.channels.ChannelsTab
import app.light.wallet.ui.home.MainTab
import app.light.wallet.ui.invoices.InvoicesTab
import app.light.wallet.ui.onboarding.OnboardingFlow
import app.light.wallet.ui.settings.SettingsTab
import app.light.wallet.ui.sheets.SheetHost
import app.light.wallet.ui.sheets.WalletSheet
import app.light.wallet.ui.theme.Tokens
import app.light.wallet.ui.transactions.TransactionsTab

enum class Tab(val label: String, val icon: ImageVector) {
    MAIN("Main", Icons.Outlined.Home),
    INVOICES("Invoices", Icons.Outlined.ReceiptLong),
    TRANSACTIONS("Transactions", Icons.Outlined.SwapHoriz),
    CHANNELS("Channels", Icons.Outlined.Hub),
    UTXOS("UTXOs", Icons.Outlined.ViewInAr),
    SETTINGS("Settings", Icons.Outlined.Settings),
}

@Composable
fun AppRoot(repository: WalletRepository) {
    var onboarded by remember { mutableStateOf(repository.hasWallet()) }

    if (!onboarded) {
        Box(modifier = Modifier.fillMaxSize()) {
            OnboardingFlow(repository = repository, onDone = {
                onboarded = true
                repository.connectInBackground()
            })
            // Seed copy happens here, before the main scaffold (and its
            // toast host) exists.
            app.light.wallet.ui.ToastHost(
                Modifier.align(Alignment.BottomCenter).navigationBarsPadding().padding(bottom = 32.dp),
            )
        }
        return
    }

    var tab by remember { mutableStateOf(Tab.MAIN) }
    var sheet by remember { mutableStateOf<WalletSheet?>(null) }
    // (counter, segment): asks the Invoices tab to select a segment.
    var invoicesSegmentRequest by remember { mutableStateOf(0 to 0) }
    // (counter, txid): asks the Transactions tab to expand one card.
    var txExpandRequest by remember { mutableStateOf<Pair<Int, String?>>(0 to null) }

    // Two-level back model: an open sheet consumes back first (handled by
    // ModalBottomSheet itself, returning to the screen that opened it);
    // otherwise back from any tab lands on Main; back on Main exits.
    BackHandler(enabled = sheet == null && tab != Tab.MAIN) {
        tab = Tab.MAIN
    }
    val active by repository.activePayments.collectAsState()
    val hasPending = active.any { it.status == ActivePaymentStatus.PAYING || it.status == ActivePaymentStatus.PENDING }

    Box(modifier = Modifier.fillMaxSize().background(Tokens.Bg)) {
        Column(modifier = Modifier.fillMaxSize()) {
            Box(modifier = Modifier.weight(1f).statusBarsPadding()) {
                AnimatedContent(
                    targetState = tab,
                    transitionSpec = {
                        // Fast, light: a short directional slide + crossfade with
                        // the container size snapped (no slow spring relayout that
                        // makes the screen feel like it takes a moment to open).
                        val dir = if (targetState.ordinal > initialState.ordinal) 1 else -1
                        (fadeIn(tween(110)) +
                            slideInHorizontally(tween(150)) { (dir * it) / 14 }) togetherWith
                            fadeOut(tween(90)) using
                            androidx.compose.animation.SizeTransform(clip = false) { _, _ ->
                                androidx.compose.animation.core.snap()
                            }
                    },
                    label = "tab",
                ) { t ->
                    when (t) {
                        Tab.MAIN -> MainTab(
                            repository,
                            onOpenSheet = { sheet = it },
                            onGoTransactions = { tab = Tab.TRANSACTIONS },
                            onRecentInvoice = { inv ->
                                tab = Tab.INVOICES
                                invoicesSegmentRequest = invoicesSegmentRequest.first + 1 to 0
                                sheet = WalletSheet.InvoiceDetail(inv)
                            },
                            onRecentPay = { p ->
                                tab = Tab.INVOICES
                                invoicesSegmentRequest = invoicesSegmentRequest.first + 1 to 1
                                sheet = WalletSheet.PaymentDetail(p)
                            },
                            onRecentTx = { txid ->
                                tab = Tab.TRANSACTIONS
                                txExpandRequest = txExpandRequest.first + 1 to txid
                            },
                        )
                        Tab.INVOICES -> InvoicesTab(repository,
                            onOpenSheet = { sheet = it },
                            segmentRequest = invoicesSegmentRequest)
                        Tab.TRANSACTIONS -> TransactionsTab(repository, expandRequest = txExpandRequest)
                        Tab.CHANNELS -> ChannelsTab(repository, onOpenSheet = { sheet = it })
                        Tab.UTXOS -> app.light.wallet.ui.utxo.UtxoTab(repository)
                        Tab.SETTINGS -> SettingsTab(repository,
                            onOpenSheet = { sheet = it },
                            onWalletWiped = { onboarded = false })
                    }
                }
            }
            TabBar(tab, hasPending) { tab = it }
        }
        // Design toast pill (white, bottom-centered, auto-dismiss). Sheets
        // draw their own (see SheetHost) since they cover this one.
        app.light.wallet.ui.ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 110.dp))

        // Boot pill: floating status for the whole wait - connecting to
        // ember, then the node being set up or woken from idle (which can
        // take tens of seconds).
        val connState by repository.connState.collectAsState()
        val waiting = connState is ConnState.Connecting || connState is ConnState.Provisioning
        androidx.compose.animation.AnimatedVisibility(
            visible = waiting,
            enter = fadeIn(tween(200)) + androidx.compose.animation.slideInVertically(tween(250)) { -it },
            exit = fadeOut(tween(250)) + androidx.compose.animation.slideOutVertically(tween(250)) { -it },
            modifier = Modifier
                .align(Alignment.TopCenter)
                .statusBarsPadding()
                .padding(top = 52.dp),
        ) {
            NodeWaitPill(connState)
        }
        SheetHost(
            repository, sheet,
            onClose = { sheet = null },
            onOpenSheet = { sheet = it },
            onWalletWiped = { onboarded = false },
            onPaymentStarted = { id ->
                // Design flow: land on Invoices -> Payments with the live
                // payment detail sheet on top.
                tab = Tab.INVOICES
                invoicesSegmentRequest = invoicesSegmentRequest.first + 1 to 1
                sheet = WalletSheet.ActivePaymentDetail(id)
            },
        )
    }
}

@Composable
private fun TabBar(current: Tab, hasPending: Boolean, onPick: (Tab) -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Sheet.copy(alpha = .95f))
            .padding(top = 8.dp)
            .navigationBarsPadding(),
    ) {
        Tab.entries.forEach { t ->
            val color = if (t == current) Tokens.Accent else Tokens.Faint
            Column(
                modifier = Modifier
                    .weight(1f)
                    .noRipple { onPick(t) }
                    .padding(top = 6.dp, bottom = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Box {
                    Icon(t.icon, contentDescription = t.label, tint = color, modifier = Modifier.size(21.dp))
                    if (t == Tab.INVOICES && hasPending) {
                        Icon(
                            Icons.Filled.Circle, contentDescription = "pending",
                            tint = Tokens.Orange,
                            modifier = Modifier.size(8.dp).align(Alignment.TopEnd),
                        )
                    }
                }
                Text(
                    t.label,
                    // Design is 10px; the longest label ("Transactions") needs
                    // one step down to survive 360dp-wide screens with 6 tabs.
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontSize = if (t.label.length > 10) 9.sp else 10.sp,
                    ),
                    color = color,
                    maxLines = 1,
                    softWrap = false,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
        }
    }
}

/**
 * Floating "please wait" pill: spinner, what's happening, how long it's
 * been, and an indeterminate progress sweep. Keeps showing the last
 * state's text while it animates out.
 */
@Composable
private fun NodeWaitPill(connState: ConnState) {
    var last by remember { mutableStateOf(connState) }
    if (connState is ConnState.Connecting || connState is ConnState.Provisioning) last = connState
    val (title, hint) = when (val s = last) {
        is ConnState.Provisioning -> when (s.phase) {
            app.light.wallet.core.NodePhase.STOPPED -> "Waking your node…" to "It was asleep after inactivity"
            else -> "Starting your node…" to "Usually takes under a minute"
        }
        else -> "Connecting to server…" to "Establishing a secure connection"
    }

    // Seconds since the wait began (resets only when the pill reappears).
    var elapsed by remember { mutableStateOf(0) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            kotlinx.coroutines.delay(1000)
            elapsed++
        }
    }

    val pulse = androidx.compose.animation.core.rememberInfiniteTransition(label = "wait")
    val glow by pulse.animateFloat(
        initialValue = .25f, targetValue = .7f,
        animationSpec = androidx.compose.animation.core.infiniteRepeatable(
            tween(900), androidx.compose.animation.core.RepeatMode.Reverse,
        ),
        label = "glow",
    )

    Column(
        modifier = Modifier
            .padding(horizontal = 24.dp)
            .background(Color(0xF210181E), RoundedCornerShape(18.dp))
            .border(1.dp, Tokens.Accent.copy(alpha = glow), RoundedCornerShape(18.dp))
            .padding(start = 16.dp, end = 16.dp, top = 11.dp, bottom = 10.dp),
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Tokens.Accent, strokeWidth = 2.dp,
                modifier = Modifier.size(16.dp),
            )
            Column {
                androidx.compose.animation.AnimatedContent(
                    targetState = title,
                    transitionSpec = { fadeIn(tween(200)) togetherWith fadeOut(tween(150)) },
                    label = "waitTitle",
                ) { t ->
                    Text(
                        t,
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontWeight = androidx.compose.ui.text.font.FontWeight.Bold),
                        color = Tokens.Text,
                    )
                }
                Text(
                    if (elapsed >= 3) "$hint · ${elapsed}s" else hint,
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp),
                    color = Tokens.TextMid,
                )
            }
        }
        androidx.compose.material3.LinearProgressIndicator(
            color = Tokens.Accent,
            trackColor = Tokens.Accent.copy(alpha = .12f),
            modifier = Modifier
                .padding(top = 9.dp)
                .width(220.dp)
                .height(3.dp)
                .clip(RoundedCornerShape(999.dp)),
        )
    }
}
