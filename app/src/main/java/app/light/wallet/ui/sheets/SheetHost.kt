package app.light.wallet.ui.sheets

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.filled.Bolt
import androidx.compose.material.icons.outlined.ContentCopy
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.ModalBottomSheetProperties
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.light.wallet.core.CloseChannelParams
import app.light.wallet.core.CreateInvoiceParams
import app.light.wallet.core.DecodedInvoice
import app.light.wallet.core.OpenChannelParams
import app.light.wallet.core.WithdrawParams
import app.light.wallet.core.XpayParams
import app.light.wallet.data.ActivePaymentStatus
import app.light.wallet.data.ConnState
import app.light.wallet.data.PayCommand
import app.light.wallet.data.WalletRepository
import app.light.wallet.ui.noRipple
import app.light.wallet.ui.DangerPillButton
import app.light.wallet.ui.KvPanel
import app.light.wallet.ui.PillButton
import app.light.wallet.ui.SoftPillButton
import app.light.wallet.ui.DisplayUnit
import app.light.wallet.ui.copyToClipboard
import app.light.wallet.ui.expiresIn
import app.light.wallet.ui.DEFAULT_ASSET_PRECISION
import app.light.wallet.ui.assetPrecisionOf
import app.light.wallet.ui.assetSymbolOf
import app.light.wallet.ui.filterAssetAmountInput
import app.light.wallet.ui.formatAmount
import app.light.wallet.ui.formatAssetUnits
import app.light.wallet.ui.parseAssetAmountToMsat
import app.light.wallet.ui.filterAmountInput
import app.light.wallet.ui.formatAmountFull
import app.light.wallet.ui.parseAmountToMsat
import app.light.wallet.ui.formatUnixTime
import app.light.wallet.ui.qrBitmap
import app.light.wallet.ui.shortId
import app.light.wallet.ui.theme.MonoFont
import app.light.wallet.ui.theme.SpaceGrotesk
import app.light.wallet.ui.theme.Tokens
import kotlinx.coroutines.launch

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SheetHost(
    repository: WalletRepository,
    sheet: WalletSheet?,
    onClose: () -> Unit,
    onOpenSheet: (WalletSheet) -> Unit,
    onWalletWiped: () -> Unit = {},
    onPaymentStarted: (Long) -> Unit = {},
) {
    if (sheet == null) return
    // The sheet renders in its OWN window, which the Activity's FLAG_SECURE does
    // not cover. Key on `secure` so entering the seed sheet always builds a
    // fresh, secure-born window — even if a future change opens it from within
    // another live sheet (a plain content-swap would otherwise keep the old,
    // non-secure window).
    val secure = sheet is WalletSheet.Seed
    androidx.compose.runtime.key(secure) {
    ModalBottomSheet(
        onDismissRequest = onClose,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = Tokens.Sheet,
        properties = ModalBottomSheetProperties(
            securePolicy = if (secure)
                androidx.compose.ui.window.SecureFlagPolicy.SecureOn
            else
                androidx.compose.ui.window.SecureFlagPolicy.Inherit,
        ),
        dragHandle = {
            Box(
                Modifier.padding(top = 14.dp, bottom = 0.dp).size(width = 40.dp, height = 4.dp)
                    .background(Color(0x26FFFFFF), RoundedCornerShape(999.dp)),
            )
        },
    ) {
        // The sheet is its own window above the activity, so AppRoot's toast
        // would be hidden behind it - "copied" etc. need a host in here too.
        Box(modifier = Modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp)
                .padding(top = 14.dp, bottom = 40.dp),
        ) {
            when (sheet) {
                is WalletSheet.Receive -> ReceiveSheet(repository, sheet.assetSymbol, sheet.assetId, onClose)
                is WalletSheet.SendChain -> SendChainSheet(repository, onClose)
                is WalletSheet.SendAsset -> SendAssetSheet(repository, sheet, onOpenSheet, onClose)
                is WalletSheet.TopUpAssetWallet -> TopUpAssetWalletSheet(repository, onClose)
                is WalletSheet.Faucet -> FaucetSheet(repository, sheet, onClose)
                is WalletSheet.NewInvoice -> NewInvoiceSheet(repository, sheet.assetId, onOpenSheet, onClose)
                is WalletSheet.NewInvoiceResult -> InvoiceResultSheet(sheet, onClose)
                is WalletSheet.Pay -> PaySheet(repository, sheet.prefill, onPaymentStarted, onClose)
                is WalletSheet.OpenChannel -> OpenChannelSheet(repository, sheet.assetId, onOpenSheet, onClose)
                is WalletSheet.InvoiceDetail -> InvoiceDetailSheet(repository, sheet, onClose)
                is WalletSheet.PaymentDetail -> PaymentDetailSheet(repository, sheet, onClose)
                is WalletSheet.ActivePaymentDetail -> ActivePaymentSheet(repository, sheet.paymentId, onClose)
                is WalletSheet.ChannelDetail -> ChannelDetailSheet(repository, sheet, onClose)
                is WalletSheet.CheckRoute -> CheckRouteSheet(repository, onClose)
                is WalletSheet.NodeInfoSheet -> NodeSheet(repository, onClose)
                is WalletSheet.Seed -> SeedSheet(repository, onClose)
                is WalletSheet.DropWallet -> DropSheet(repository, onCancel = onClose) { onClose(); onWalletWiped() }
            }
        }
        app.light.wallet.ui.ToastHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 16.dp))
        }
    }
    }
}

@Composable
private fun SheetTitle(text: String, onClose: (() -> Unit)? = null, badge: String? = null) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(text, style = MaterialTheme.typography.titleMedium)
        if (badge != null) {
            Text(
                badge,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFont, fontSize = 11.5.sp),
                color = Tokens.Accent,
                modifier = Modifier
                    .padding(start = 10.dp)
                    .background(Tokens.Accent.copy(alpha = .08f), RoundedCornerShape(999.dp))
                    .border(1.dp, Tokens.Accent.copy(alpha = .25f), RoundedCornerShape(999.dp))
                    .padding(horizontal = 11.dp, vertical = 4.dp),
            )
        }
        androidx.compose.foundation.layout.Spacer(Modifier.weight(1f))
        if (onClose != null) {
            Box(
                modifier = Modifier
                    .size(30.dp)
                    .background(Color(0x12FFFFFF), androidx.compose.foundation.shape.CircleShape)
                    .noRipple(onClick = onClose),
                contentAlignment = Alignment.Center,
            ) {
                Text("✕", color = Tokens.TextMid, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

@Composable
private fun FieldLabel(text: String, topPadding: Int = 4) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
        color = Tokens.Dim,
        modifier = Modifier.padding(top = topPadding.dp, bottom = 8.dp),
    )
}

@Composable
private fun DesignField(
    value: String,
    onValue: (String) -> Unit,
    placeholder: String,
    mono: Boolean = false,
    numeric: Boolean = false,
    big: Boolean = false,
    lines: Int = 1,
    enabled: Boolean = true,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onValue,
        enabled = enabled,
        placeholder = { Text(placeholder, color = Tokens.Faint) },
        minLines = lines,
        maxLines = if (lines > 1) lines + 1 else 1,
        keyboardOptions = if (numeric) KeyboardOptions(keyboardType = KeyboardType.Decimal) else KeyboardOptions.Default,
        textStyle = when {
            big -> TextStyle(fontFamily = SpaceGrotesk, fontWeight = FontWeight.SemiBold, fontSize = 20.sp, color = Tokens.Text)
            mono -> TextStyle(fontFamily = MonoFont, fontSize = 12.sp, color = Tokens.TextMid, lineHeight = 18.sp)
            else -> TextStyle(fontFamily = MaterialTheme.typography.bodyLarge.fontFamily, fontSize = 14.sp, color = Tokens.Text)
        }.let { if (enabled) it else it.copy(color = Tokens.Faint) },
        shape = RoundedCornerShape(13.dp),
        colors = OutlinedTextFieldDefaults.colors(
            focusedContainerColor = Tokens.CardDeep,
            unfocusedContainerColor = Tokens.CardDeep,
            focusedBorderColor = Tokens.Accent,
            unfocusedBorderColor = Color(0x17FFFFFF),
            cursorColor = Tokens.Accent,
            // Locked fields (e.g. the swap-service peer) read as read-only:
            // flatter grey fill, faint border and text.
            disabledContainerColor = Color(0x0AFFFFFF),
            disabledBorderColor = Color(0x0DFFFFFF),
            disabledTextColor = Tokens.Faint,
        ),
        modifier = Modifier.fillMaxWidth(),
    )
}

@Composable
private fun QrBox(data: String, sizeDp: Int = 188) {
    val bmp = remember(data) { qrBitmap(data) }
    if (bmp != null) {
        Box(
            modifier = Modifier.background(Color.White, RoundedCornerShape(18.dp)).padding(12.dp),
        ) {
            Image(bmp, contentDescription = "QR", modifier = Modifier.size(sizeDp.dp))
        }
    }
}

// ─────────────────────────── Receive (on-chain) ───────────────────────────

@Composable
private fun ReceiveSheet(repository: WalletRepository, assetSymbol: String?, assetId: String?, onClose: () -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var address by remember { mutableStateOf<String?>(null) }
    var error by remember { mutableStateOf<String?>(null) }

    fun generate() {
        scope.launch {
            // An issued asset goes to the asset wallet: CLN's own wallet
            // (newaddr) ignores every non-L-BTC output.
            runCatching {
                if (assetId != null) repository.newAssetAddress()
                else repository.requireNode().newAddress("bech32").bech32
            }
                .onSuccess { address = it }
                .onFailure { error = it.message }
        }
    }

    androidx.compose.runtime.LaunchedEffect(Unit) { generate() }

    SheetTitle("Receive ${assetSymbol ?: "L-BTC"}", onClose)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        address?.let { addr ->
            QrBox(addr.uppercase())
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Tokens.Field, RoundedCornerShape(14.dp))
                    .border(1.dp, Tokens.Border, RoundedCornerShape(14.dp))
                    .noRipple { copyToClipboard(context, "Address", addr) }
                    .padding(horizontal = 15.dp, vertical = 13.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Text(
                    addr,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.sp),
                    color = Tokens.TextMid, modifier = Modifier.weight(1f),
                )
                androidx.compose.material3.Icon(
                    androidx.compose.material.icons.Icons.Outlined.ContentCopy,
                    contentDescription = "Copy address",
                    tint = Tokens.Accent, modifier = Modifier.size(16.dp),
                )
            }
            PillButton("Copy address", modifier = Modifier.fillMaxWidth(), height = 50) {
                copyToClipboard(context, "Address", addr)
            }
            Text(
                if (assetId != null) "Asset wallet address · receives any issued asset; L-BTC sent here tops up its fee balance"
                else "bech32 · newaddr · a fresh address is generated after each use",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Tokens.Faint,
            )
            app.light.wallet.ui.OutlinePillButton("Generate new address", height = 46) { generate() }
        } ?: run {
            error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall) }
                ?: Text("Generating address…", color = Tokens.Dim, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

// ─────────────────────────── Faucet ───────────────────────────

/** "1d 02:03:04" / "02:03:04" / "03:04" until [seconds] are up. */
private fun formatCountdown(seconds: Long): String {
    val s = seconds.coerceAtLeast(0)
    val d = s / 86_400
    val h = s % 86_400 / 3_600
    val m = s % 3_600 / 60
    val sec = s % 60
    return when {
        d > 0 -> String.format(java.util.Locale.US, "%dd %02d:%02d:%02d", d, h, m, sec)
        h > 0 -> String.format(java.util.Locale.US, "%02d:%02d:%02d", h, m, sec)
        else -> String.format(java.util.Locale.US, "%02d:%02d", m, sec)
    }
}

/** "every 24h" / "every 30m" / "every 90s". */
private fun formatInterval(seconds: ULong): String {
    val s = seconds.toLong()
    return when {
        s > 0 && s % 86_400 == 0L -> "every ${s / 86_400}d"
        s > 0 && s % 3_600 == 0L -> "every ${s / 3_600}h"
        s > 0 && s % 60 == 0L -> "every ${s / 60}m"
        else -> "every ${s}s"
    }
}

/** The server's own message out of a gRPC error string, when there is one. */
private fun grpcMessage(raw: String?): String {
    val text = raw ?: "failed"
    val m = Regex("message: \"([^\"]*)\"").find(text)
    return m?.groupValues?.get(1)?.ifBlank { null } ?: text
}

@Composable
private fun FaucetSheet(repository: WalletRepository, sheet: WalletSheet.Faucet, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val assets by repository.assets.collectAsState()
    val scope = rememberCoroutineScope()
    val asset = sheet.assetId ?: "lbtc"
    var info by remember { mutableStateOf<app.light.wallet.core.FaucetInfo?>(null) }
    var loadError by remember { mutableStateOf<String?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var claimed by remember { mutableStateOf<app.light.wallet.core.FaucetClaim?>(null) }
    // Unix seconds, ticking once a second for the countdown.
    var now by remember { mutableStateOf(System.currentTimeMillis() / 1000) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        while (true) {
            now = System.currentTimeMillis() / 1000
            kotlinx.coroutines.delay(1_000)
        }
    }
    suspend fun load() {
        runCatching { repository.getFaucet(asset) }
            .onSuccess { info = it; loadError = null }
            .onFailure { loadError = grpcMessage(it.message) }
    }
    androidx.compose.runtime.LaunchedEffect(asset) { load() }

    fun amount(msat: ULong) = app.light.wallet.ui.formatAmountIn(msat, sheet.assetId, assets, unit)

    SheetTitle("${sheet.symbol} faucet", onClose, badge = "testnet")
    val f = info
    when {
        f == null -> Text(
            loadError ?: "Loading faucet…",
            color = if (loadError != null) Tokens.Red else Tokens.Dim,
            style = MaterialTheme.typography.bodyMedium,
        )
        !f.enabled -> Text(
            "There's no faucet for ${sheet.symbol} right now.",
            color = Tokens.Dim, style = MaterialTheme.typography.bodyMedium,
        )
        else -> Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
                Box(
                    modifier = Modifier.size(56.dp).background(Tokens.Accent.copy(alpha = .1f), androidx.compose.foundation.shape.CircleShape),
                    contentAlignment = Alignment.Center,
                ) {
                    androidx.compose.material3.Icon(app.light.wallet.ui.FaucetIcon, null, tint = Tokens.Accent, modifier = Modifier.size(26.dp))
                }
                Text(
                    amount(f.amountMsat),
                    style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
                    modifier = Modifier.padding(top = 12.dp),
                )
                Text("per claim", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint)
            }
            KvPanel(
                listOfNotNull(
                    Triple("Faucet balance", f.balanceMsat?.let { amount(it) } ?: "—", false),
                    Triple("Claims", if (f.unlimited) "unlimited for this node" else formatInterval(f.intervalSecs), false),
                    Triple("Sent to", if (sheet.assetId != null) "asset wallet" else "main wallet", false),
                ),
            )
            claimed?.let { c ->
                Text("Sent ${amount(c.amountMsat)} ✓", style = MaterialTheme.typography.titleSmall, color = Tokens.Green)
                Text(
                    c.txid,
                    style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.sp),
                    color = Tokens.Dim,
                )
            }
            error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall) }
            val waitSecs = if (f.unlimited || f.nextClaimAt == 0uL) 0L else f.nextClaimAt.toLong() - now
            if (waitSecs > 0) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(Tokens.Field, RoundedCornerShape(14.dp))
                        .border(1.dp, Tokens.Border, RoundedCornerShape(14.dp))
                        .padding(vertical = 14.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text("Next claim in", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint)
                    Text(
                        formatCountdown(waitSecs),
                        style = MaterialTheme.typography.titleLarge.copy(fontFamily = MonoFont),
                        color = Tokens.TextMid, modifier = Modifier.padding(top = 4.dp),
                    )
                }
            } else {
                // The faucet can't pay a full claim: nothing to press.
                val tooLittle = f.balanceMsat?.let { it < f.amountMsat } ?: false
                if (tooLittle) {
                    Text(
                        "The faucet has less than one claim left.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Tokens.Orange,
                    )
                }
                PillButton(
                    when {
                        busy -> "Sending…"
                        tooLittle -> "Not enough in the faucet"
                        else -> "Get ${amount(f.amountMsat)}"
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !busy && !tooLittle,
                ) {
                    busy = true; error = null
                    scope.launch {
                        runCatching { repository.claimFaucet(asset) }
                            .onSuccess { c ->
                                claimed = c
                                info = f.copy(nextClaimAt = c.nextClaimAt)
                                load() // fresh faucet balance
                            }
                            .onFailure {
                                error = grpcMessage(it.message)
                                load() // e.g. already claimed elsewhere: show the countdown
                            }
                        busy = false
                    }
                }
            }
        }
    }
}

// ─────────────────────────── Send (on-chain) ───────────────────────────

@Composable
private fun SendChainSheet(repository: WalletRepository, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    var addr by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var sendAll by remember { mutableStateOf(false) }
    var feerate by remember { mutableStateOf("normal") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var txid by remember { mutableStateOf<String?>(null) }

    SheetTitle("Send L-BTC", onClose)
    txid?.let {
        Text("Broadcast ✓", style = MaterialTheme.typography.titleSmall, color = Tokens.Green)
        Text(
            it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp),
            color = Tokens.Dim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        PillButton("Done", modifier = Modifier.fillMaxWidth()) { onClose() }
        return
    }
    FieldLabel("Destination address")
    DesignField(addr, { addr = it }, "ex1q…", mono = true)
    FieldLabel("Amount (${unit.label})", topPadding = 12)
    DesignField(amount, { amount = filterAmountInput(it, unit); sendAll = false }, "0", numeric = true, big = true)
    run {
        val funds by repository.funds.collectAsState()
        val available = funds?.let { it.onchainConfirmedMsat / 1000uL } ?: 0uL
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            Text(
                "Available: ${formatAmount(available * 1000uL, unit)} ${unit.label}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
                modifier = Modifier.weight(1f),
            )
            Text(
                "Send max",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = Tokens.Accent,
                modifier = Modifier.noRipple { sendAll = true; amount = formatAmount(available * 1000uL, unit).replace(" ", "") },
            )
        }
    }
    FieldLabel("Feerate", topPadding = 12)
    app.light.wallet.ui.FeerateChips(selected = feerate, onSelect = { feerate = it })
    if (false) Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf("slow" to "~1 day", "normal" to "~1 hour", "urgent" to "next block").forEach { (v, sub) ->
            val on = feerate == v
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(if (on) Tokens.Accent.copy(alpha = .1f) else Tokens.CardDeep, RoundedCornerShape(12.dp))
                    .border(1.dp, if (on) Tokens.Accent.copy(alpha = .4f) else Color(0x17FFFFFF), RoundedCornerShape(12.dp))
                    .noRipple { feerate = v }
                    .padding(vertical = 10.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(v, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = if (on) Tokens.Accent else Tokens.TextMid)
                Text(sub, style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint, modifier = Modifier.padding(top = 2.dp))
            }
        }
    }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    PillButton(
        if (busy) "Broadcasting…" else "Send on-chain",
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        enabled = !busy && addr.isNotBlank() && parseAmountToMsat(amount, unit) != null,
    ) {
        busy = true; error = null
        scope.launch {
            runCatching {
                repository.requireNode().withdraw(
                    WithdrawParams(
                        destination = addr.trim(),
                        amountSat = if (sendAll) 0uL else (parseAmountToMsat(amount, unit) ?: 0uL) / 1000uL,
                        all = sendAll, minconf = null, feerate = feerate,
                    ),
                )
            }.onSuccess { txid = it.txid }.onFailure { error = it.message }
            busy = false
        }
    }
}

@Composable
private fun AssetPill(label: String, selected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .background(if (selected) Tokens.Accent.copy(alpha = .1f) else Tokens.CardDeep, RoundedCornerShape(999.dp))
            .border(1.dp, if (selected) Tokens.Accent.copy(alpha = .4f) else Color(0x17FFFFFF), RoundedCornerShape(999.dp))
            .noRipple(onClick = onClick)
            .padding(horizontal = 13.dp, vertical = 8.dp),
    ) {
        Text(
            label,
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
            color = if (selected) Tokens.Accent else Tokens.Dim,
        )
    }
}

// ─────────────────────────── New invoice ───────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun NewInvoiceSheet(
    repository: WalletRepository,
    initialAssetId: String?,
    onOpenSheet: (WalletSheet) -> Unit,
    onClose: () -> Unit,
) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    val availableAssets by repository.assets.collectAsState()
    // null = this node's own native coin (a plain invoice via createInvoice).
    var selectedAsset by remember {
        mutableStateOf(availableAssets?.firstOrNull { it.id == initialAssetId })
    }
    // Opened from an asset page before list_assets arrived: preselect once it does.
    var preselected by remember { mutableStateOf(selectedAsset != null || initialAssetId == null) }
    androidx.compose.runtime.LaunchedEffect(availableAssets) {
        if (!preselected) availableAssets?.firstOrNull { it.id == initialAssetId }?.let {
            selectedAsset = it; preselected = true
        }
    }
    var anyAmount by remember { mutableStateOf(false) }
    var amount by remember { mutableStateOf("") }
    var desc by remember { mutableStateOf("") }
    var expD by remember { mutableStateOf("7") }
    var expH by remember { mutableStateOf("0") }
    var expM by remember { mutableStateOf("0") }
    var showAdv by remember { mutableStateOf(false) }
    var customLabel by remember { mutableStateOf("") }
    var cltv by remember { mutableStateOf("") }
    var fallback by remember { mutableStateOf("") }
    var preimageAuto by remember { mutableStateOf(true) }
    var preimageHex by remember { mutableStateOf("") }
    var exposeMode by remember { mutableStateOf("default") } // default | scids
    var exposeScids by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    // An asset invoice's amount is typed in whole asset units ("0.5"),
    // not the L-BTC display unit.
    val assetPrecision = selectedAsset?.precision?.toInt() ?: DEFAULT_ASSET_PRECISION
    fun parsedAmountMsat(): ULong? =
        if (selectedAsset != null) parseAssetAmountToMsat(amount, assetPrecision) else parseAmountToMsat(amount, unit)

    SheetTitle("Create invoice", onClose)
    if (!availableAssets.isNullOrEmpty()) {
        FieldLabel("Receive as")
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            // "any amount" only makes sense for a plain invoice - an
            // asset invoice's amount_msat is required (assetinvoice.rs
            // has no AmountOrAny handling the way plain invoice does).
            AssetPill("L-BTC", selectedAsset == null) { selectedAsset = null; anyAmount = false; preselected = true; amount = "" }
            availableAssets.orEmpty().forEach { a ->
                AssetPill(a.symbol, selectedAsset?.id == a.id) { selectedAsset = a; anyAmount = false; preselected = true; amount = "" }
            }
        }
    }
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(top = if (availableAssets.isNullOrEmpty()) 0.dp else 14.dp)) {
        Text(
            "Amount (${selectedAsset?.symbol ?: unit.label})",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
            color = Tokens.Dim, modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(7.dp),
            modifier = Modifier.noRipple { anyAmount = !anyAmount },
        ) {
            app.light.wallet.ui.DesignSwitch(checked = anyAmount) { anyAmount = !anyAmount }
            Text(
                "Any amount",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
                color = Tokens.TextMid,
            )
        }
    }
    if (!anyAmount) {
        Box(Modifier.padding(top = 8.dp)) {
            DesignField(
                amount, {
                    amount = if (selectedAsset != null) filterAssetAmountInput(it, assetPrecision) else filterAmountInput(it, unit)
                },
                if (selectedAsset != null) "0.5" else when (unit) {
                    DisplayUnit.SAT -> "25 000"
                    DisplayUnit.MSAT -> "25 000 000"
                    DisplayUnit.BTC -> "0.00025"
                },
                numeric = true, big = true,
            )
        }
    }
    FieldLabel("Description", topPadding = 12)
    DesignField(desc, { desc = it }, "What is this for?")
    FieldLabel("Expiry", topPadding = 12)
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        listOf(Triple("Days", expD) { v: String -> expD = v },
               Triple("Hours", expH) { v: String -> expH = v },
               Triple("Minutes", expM) { v: String -> expM = v }).forEach { (label, value, setter) ->
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(label, style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(value, { setter(it.filter { c -> c.isDigit() }) }, "0", numeric = true)
            }
        }
    }
    Text(
        if (showAdv) "Advanced ▲" else "Advanced ▼",
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
        color = Tokens.Accent,
        modifier = Modifier.padding(top = 10.dp).noRipple { showAdv = !showAdv },
    )
    if (showAdv) {
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Label", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(customLabel, { customLabel = it }, "auto", mono = true)
            }
            Column(modifier = Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("CLTV", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(cltv, { cltv = it.filter { c -> c.isDigit() } }, "18", numeric = true)
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Fallback address (optional)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
            DesignField(fallback, { fallback = it }, "ex1q…", mono = true)
        }
        Row(modifier = Modifier.padding(top = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Preimage",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
                color = Tokens.Faint, modifier = Modifier.weight(1f),
            )
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(7.dp),
                modifier = Modifier.noRipple { preimageAuto = !preimageAuto },
            ) {
                app.light.wallet.ui.DesignSwitch(checked = preimageAuto) { preimageAuto = !preimageAuto }
                Text("Auto-generate", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = Tokens.TextMid)
            }
        }
        if (preimageAuto) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 6.dp)
                    .background(Color(0x08FFFFFF), RoundedCornerShape(11.dp))
                    .border(1.dp, Color(0x1AFFFFFF), RoundedCornerShape(11.dp))
                    .padding(horizontal = 12.dp, vertical = 10.dp),
            ) {
                Text(
                    "Random 32-byte preimage generated by the node",
                    style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontFamily = FontFamily.Monospace),
                    color = Tokens.Faint,
                )
            }
        } else {
            Box(Modifier.padding(top = 6.dp)) {
                DesignField(preimageHex, { preimageHex = it.trim() }, "64 hex chars", mono = true)
            }
        }
        Text(
            "Expose private channels",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold),
            color = Tokens.Faint, modifier = Modifier.padding(top = 10.dp, bottom = 6.dp),
        )
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf("default" to "Default", "true" to "True", "false" to "False", "scids" to "SCIDs").forEach { (v, label) ->
                val on = exposeMode == v
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .background(if (on) Tokens.Accent.copy(alpha = .1f) else Tokens.CardDeep, RoundedCornerShape(11.dp))
                        .border(1.dp, if (on) Tokens.Accent.copy(alpha = .4f) else Color(0x17FFFFFF), RoundedCornerShape(11.dp))
                        .noRipple { exposeMode = v }
                        .padding(vertical = 9.dp),
                    contentAlignment = Alignment.Center,
                ) {
                    Text(label, style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = if (on) Tokens.Accent else Tokens.Dim)
                }
            }
        }
        if (exposeMode == "scids") {
            Box(Modifier.padding(top = 6.dp)) {
                DesignField(exposeScids, { exposeScids = it }, "848291x1204x1, 851002x88x0", mono = true)
            }
        }
        Text(
            "Boolean or a list of short_channel_ids — picking one form rules out the others",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp),
            color = Tokens.Faint, modifier = Modifier.padding(top = 6.dp),
        )
    }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    PillButton(
        if (busy) "Creating…" else "Create invoice",
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        enabled = !busy && ((selectedAsset == null && anyAmount) || parsedAmountMsat() != null),
    ) {
        busy = true; error = null
        scope.launch {
            val asset = selectedAsset
            if (asset != null) {
                // assetinvoice has no "any amount" equivalent - the amount
                // field is required (assetswap-plugin's own RPC takes a
                // plain amount_msat, not CLN's AmountOrAny).
                val amountMsat = parsedAmountMsat()
                if (amountMsat == null) {
                    error = "amount is required for an asset invoice"
                    busy = false
                    return@launch
                }
                runCatching {
                    repository.createAssetInvoice(
                        asset.id, amountMsat,
                        customLabel.ifBlank { "lightapp-${System.currentTimeMillis()}" },
                        desc,
                    )
                }.onSuccess {
                    onOpenSheet(WalletSheet.NewInvoiceResult(it.bolt11, it.paymentHash, 0uL, wrapped = it.wrapped))
                }.onFailure { error = it.message }
                busy = false
                return@launch
            }
            val expiry = (expD.toULongOrNull() ?: 0uL) * 86400uL +
                (expH.toULongOrNull() ?: 0uL) * 3600uL + (expM.toULongOrNull() ?: 0uL) * 60uL
            runCatching {
                repository.createInvoice(
                    CreateInvoiceParams(
                        amountMsat = if (anyAmount) null else parseAmountToMsat(amount, unit),
                        description = desc,
                        label = customLabel.ifBlank { "lightapp-${System.currentTimeMillis()}" },
                        expiry = if (expiry == 0uL) null else expiry,
                        fallbacks = listOf(fallback).filter { it.isNotBlank() },
                        preimageHex = if (!preimageAuto) preimageHex.ifBlank { null } else null,
                        cltv = cltv.toUIntOrNull(),
                        deschashonly = null,
                        // gRPC only carries scid arrays; True = every private
                        // channel we know, False = a filter matching nothing.
                        exposeprivatechannels = when (exposeMode) {
                            "scids" -> exposeScids.split(",").map { it.trim() }.filter { it.isNotEmpty() }
                            "true" -> repository.channels.value.orEmpty()
                                .filter { it.isPrivate == true }
                                .mapNotNull { it.shortChannelId }
                            "false" -> listOf("0x0x0")
                            else -> emptyList()
                        },
                    ),
                )
            }.onSuccess {
                onOpenSheet(WalletSheet.NewInvoiceResult(it.bolt11, it.paymentHash, it.expiresAt))
            }.onFailure { error = it.message }
            busy = false
        }
    }
}

@Composable
private fun InvoiceResultSheet(sheet: WalletSheet.NewInvoiceResult, onClose: () -> Unit) {
    val context = LocalContext.current
    // An asset invoice's QR/share payload is the wrapper string, not
    // bolt11 - BOLT11 has no asset field at all, so a payer's wallet
    // would never know this invoice requires a specific asset otherwise.
    val payload = sheet.wrapped ?: sheet.bolt11
    SheetTitle(if (sheet.wrapped != null) "Asset invoice created" else "Invoice created", onClose)
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        QrBox(payload.uppercase(), sizeDp = 200)
        Text(
            shortId(payload, 22),
            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp),
            color = Tokens.Dim,
        )
        if (sheet.wrapped == null) {
            Text("Expires ${formatUnixTime(sheet.expiresAt)}", style = MaterialTheme.typography.bodySmall, color = Tokens.Faint)
        }
        PillButton(
            if (sheet.wrapped != null) "Copy asset invoice" else "Copy invoice",
            modifier = Modifier.fillMaxWidth(), height = 48,
        ) {
            copyToClipboard(context, "Invoice", payload)
        }
    }
}

// ─────────────────────────── Pay an asset invoice ───────────────────────────

/**
 * Paying a wrapped asset-invoice string - a separate, simpler flow from
 * [PaySheet]'s xpay one: no route/amount decode (the wrapper carries the
 * invoice's own amount, and the swap provider decides live whether it's
 * payable), and the payment happens inline here rather than through the
 * background-tracked payment system, since it's a single RPC call, not a
 * multi-attempt xpay.
 */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun AssetInvoicePaySection(repository: WalletRepository, wrapped: String, onClose: () -> Unit) {
    val scope = rememberCoroutineScope()
    val availableAssets by repository.assets.collectAsState()
    // null = automatic: try paying in the invoice's own asset directly
    // first, falling back to a swap only if that fails (payassetinvoice's
    // own default - see its doc comment).
    var fromAsset by remember { mutableStateOf<app.light.wallet.core.AssetInfo?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<app.light.wallet.core.PayAssetInvoiceResult?>(null) }
    val invoice = remember(wrapped) { decodeAssetInvoiceWrapper(wrapped) }
    // The swap price comes from ember's price pairs (every node's
    // pricetiers-plugin polls the same table), so this node's own quote is
    // what the swap provider will accept too. Several assets can pay for the
    // same invoice asset, each its own pair: re-quote for the chosen "Pay
    // with" asset (Automatic / the invoice's own asset: the first pair).
    val quoteFrom = fromAsset?.id?.takeIf { it != invoice?.asset }
    var quote by remember(wrapped) { mutableStateOf<app.light.wallet.core.AssetQuote?>(null) }
    var quoteError by remember(wrapped) { mutableStateOf<String?>(null) }
    androidx.compose.runtime.LaunchedEffect(wrapped, quoteFrom) {
        quote = null
        quoteError = null
        runCatching { repository.quoteAssetInvoice(wrapped, quoteFrom) }
            .onSuccess { quote = it }
            .onFailure { quoteError = it.message ?: "no price available" }
    }
    fun symbolOf(id: String): String =
        availableAssets?.firstOrNull { it.id == id }?.symbol
            ?: if (id == "lbtc") "L-BTC" else id.take(8) + "…"
    fun precisionOf(id: String): Int =
        availableAssets?.firstOrNull { it.id == id }?.precision?.toInt() ?: DEFAULT_ASSET_PRECISION

    result?.let { r ->
        Column(
            modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                if (r.status.equals("complete", ignoreCase = true)) "Payment sent ✓" else "Payment ${r.status}",
                style = MaterialTheme.typography.titleSmall,
                color = if (r.status.equals("complete", ignoreCase = true)) Tokens.Green else Tokens.Orange,
            )
            PillButton("Done", modifier = Modifier.fillMaxWidth(), height = 48, onClick = onClose)
        }
        return
    }

    if (!availableAssets.isNullOrEmpty()) {
        FieldLabel("Pay with", topPadding = 14)
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            AssetPill("Automatic", fromAsset == null) { fromAsset = null }
            availableAssets.orEmpty().forEach { a ->
                AssetPill(a.symbol, fromAsset?.id == a.id) { fromAsset = a }
            }
        }
    }
    val payable = AssetPayPreview(
        invoice = invoice,
        quote = quote,
        quoteError = quoteError,
        fromAsset = fromAsset?.id,
        symbolOf = ::symbolOf,
        precisionOf = ::precisionOf,
    )
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    PillButton(
        if (busy) "Paying…" else "Pay",
        modifier = Modifier.fillMaxWidth().padding(top = 16.dp),
        enabled = !busy && payable,
    ) {
        busy = true; error = null
        scope.launch {
            runCatching { repository.payAssetInvoice(wrapped, fromAsset = fromAsset?.id) }
                .onSuccess { result = it }
                .onFailure { error = it.message }
            busy = false
        }
    }
}

/** What an asset-invoice wrapper asks for: `asset` and its `amount_msat`. */
private data class AssetInvoiceInfo(val asset: String, val amountMsat: ULong)

/**
 * The wrapper is plain URL-safe base64 JSON (assetswap-plugin's
 * `AssetInvoiceWrapper`), so its asset/amount are readable locally, no RPC.
 */
private fun decodeAssetInvoiceWrapper(wrapped: String): AssetInvoiceInfo? = runCatching {
    val json = String(
        android.util.Base64.decode(
            wrapped,
            android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
        ),
        Charsets.UTF_8,
    )
    val o = org.json.JSONObject(json)
    AssetInvoiceInfo(o.getString("asset"), o.getLong("amount_msat").toULong())
}.getOrNull()

/**
 * Wraps a plain bolt11 as an L-BTC asset invoice on the payer's side, so
 * payassetinvoice can pay it with an issued asset via the swap service.
 * The wrapper is unsigned metadata (assetswap-plugin's
 * `AssetInvoiceWrapper`): only `bolt11` is actually paid, so the payee
 * needn't have made it. Null for anything but a fixed-amount bolt11 - a
 * swap must be priced up front.
 */
private fun wrapLbtcInvoice(bolt11: String, d: DecodedInvoice): String? {
    if (d.itemType != "bolt11_invoice" || !d.valid) return null
    val amountMsat = d.amountMsat ?: return null
    val paymentHash = d.paymentHash ?: return null
    val json = org.json.JSONObject()
        .put("v", 1)
        .put("bolt11", bolt11)
        .put("payment_hash", paymentHash)
        .put("asset", "lbtc")
        .put("amount_msat", amountMsat.toLong())
        .toString()
    return android.util.Base64.encodeToString(
        json.toByteArray(Charsets.UTF_8),
        android.util.Base64.URL_SAFE or android.util.Base64.NO_PADDING or android.util.Base64.NO_WRAP,
    )
}

/**
 * Leg-1 routing-fee budget payassetinvoice allows on top of the swap amount
 * (assetswap-plugin pay.rs: `maxfee_msat = from_amount_msat / 100`).
 */
private const val SWAP_ROUTING_FEE_BUDGET_DIVISOR = 100uL

/** The tier payassetinvoice uses when the caller names none (pay.rs). */
private const val DEFAULT_SWAP_TIER = "medium"

/** "1 234 sats" for L-BTC, "0.5 USDt" (whole units, [precision] decimals) for any issued asset. */
private fun formatAssetAmount(msat: ULong, assetId: String, symbol: String, precision: Int): String =
    if (assetId == "lbtc") formatAmount(msat, DisplayUnit.SAT) + " sats"
    else formatAssetUnits(msat, precision) + " $symbol"

/**
 * Price of 1 whole `to` asset in the `from` asset's own display unit
 * (sats for L-BTC, whole units otherwise). rate_ppm is msat-per-msat, so
 * 1 whole `to` (10^toPrecision base units) costs
 * rate_ppm * 10^toPrecision / 1e6 from-base-units; kept exact.
 */
private fun formatRate(ratePpm: ULong, toPrecision: Int, fromPrecision: Int): String =
    java.math.BigDecimal(ratePpm.toLong())
        .movePointLeft(6 - toPrecision + fromPrecision)
        .stripTrailingZeros().toPlainString()

/**
 * The invoice's amount, the asset's reference price (when ember has a pair
 * for it) and what the chosen "Pay with" asset will actually cost. Returns
 * whether that choice is payable at all - false only for an asset with no
 * price pair to the invoice's asset, which payassetinvoice would refuse.
 */
@Composable
private fun AssetPayPreview(
    invoice: AssetInvoiceInfo?,
    quote: app.light.wallet.core.AssetQuote?,
    quoteError: String?,
    fromAsset: String?,
    symbolOf: (String) -> String,
    precisionOf: (String) -> Int,
): Boolean {
    fun formatAssetAmount(msat: ULong, assetId: String, symbol: String) =
        formatAssetAmount(msat, assetId, symbol, precisionOf(assetId))
    if (invoice == null) return true // unreadable wrapper: let the server decide
    val toSym = symbolOf(invoice.asset)
    val swapTier = quote?.tiers?.firstOrNull { it.name == DEFAULT_SWAP_TIER }

    @Composable
    fun Row2(label: String, value: String, valueColor: Color = Tokens.Text, bold: Boolean = false) {
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(label, style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.weight(1f))
            Text(
                value,
                style = MaterialTheme.typography.bodyMedium.copy(fontWeight = if (bold) FontWeight.Bold else FontWeight.Normal),
                color = valueColor,
            )
        }
    }

    Column(Modifier.fillMaxWidth().padding(top = 14.dp)) {
        Row2("Invoice", formatAssetAmount(invoice.amountMsat, invoice.asset, toSym))
        if (quote != null && quote.fromAsset != invoice.asset) {
            val fromSym = symbolOf(quote.fromAsset)
            val fee = if (quote.feeBaseMsat > 0uL) " + ${formatAssetAmount(quote.feeBaseMsat, quote.fromAsset, fromSym)} fee" else ""
            val fromPrecision = if (quote.fromAsset == "lbtc") 0 else precisionOf(quote.fromAsset)
            val rate = formatRate(quote.ratePpm, precisionOf(invoice.asset), fromPrecision)
            Row2("Price", "1 $toSym = $rate ${if (quote.fromAsset == "lbtc") "sat L-BTC" else fromSym}$fee")
        }

        val direct = fromAsset == invoice.asset
        val viaSwap = fromAsset != null && !direct
        when {
            direct -> Row2("You pay", formatAssetAmount(invoice.amountMsat, invoice.asset, toSym), bold = true)
            viaSwap && quote != null && fromAsset == quote.fromAsset && swapTier != null -> {
                val fromSym = symbolOf(quote.fromAsset)
                val amount = swapTier.fromAmountMsat
                val max = amount + amount / SWAP_ROUTING_FEE_BUDGET_DIVISOR
                Row2("You pay", "≈ " + formatAssetAmount(amount, quote.fromAsset, fromSym), valueColor = Tokens.Accent, bold = true)
                Text(
                    "At most ${formatAssetAmount(max, quote.fromAsset, fromSym)} including routing fees, swapped to $toSym by the swap service.",
                    style = MaterialTheme.typography.bodySmall, color = Tokens.Dim,
                    modifier = Modifier.padding(top = 4.dp),
                )
            }
            viaSwap -> {
                Text(
                    if (quote == null && quoteError == null) "Getting price…"
                    else "No price to pay $toSym with ${symbolOf(fromAsset!!)}.",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (quote == null && quoteError == null) Tokens.Dim else Tokens.Red,
                    modifier = Modifier.padding(top = 6.dp),
                )
                return false
            }
            else -> { // Automatic: direct if this node holds the asset, else a swap
                Row2("You pay", formatAssetAmount(invoice.amountMsat, invoice.asset, toSym) + " directly", bold = true)
                if (quote != null && swapTier != null && quote.fromAsset != invoice.asset) {
                    Text(
                        "or ≈ ${formatAssetAmount(swapTier.fromAmountMsat, quote.fromAsset, symbolOf(quote.fromAsset))} via swap if you don't hold enough $toSym.",
                        style = MaterialTheme.typography.bodySmall, color = Tokens.Dim,
                        modifier = Modifier.padding(top = 4.dp),
                    )
                }
            }
        }
    }
    return true
}

// ─────────────────────────── Pay (xpay) ───────────────────────────

@Composable
private fun PaySheet(repository: WalletRepository, prefill: String, onPaymentStarted: (Long) -> Unit, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    var inv by remember { mutableStateOf(prefill) }
    var decoded by remember { mutableStateOf<DecodedInvoice?>(null) }
    var showAdv by remember { mutableStateOf(false) }
    var maxFee by remember { mutableStateOf("") }
    var retryFor by remember { mutableStateOf("") }
    var maxDelay by remember { mutableStateOf("") }
    var partial by remember { mutableStateOf("") }
    var layers by remember { mutableStateOf("") }
    var amountOverride by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var decodeError by remember { mutableStateOf<String?>(null) }
    var decoding by remember { mutableStateOf(false) }
    // Pay a plain L-BTC bolt11 through the asset-swap flow instead of xpay,
    // for a payer who holds only an issued asset (see wrapLbtcInvoice).
    var payWithAsset by remember { mutableStateOf(false) }
    val availableAssets by repository.assets.collectAsState()

    // A real bolt11/offer always starts with "ln" (BOLT11/12's own
    // human-readable prefix); an asset invoice is a base64 wrapper string
    // instead (assetinvoice.rs - there's no other reliable client-side
    // signal to tell the two apart without a round trip).
    val trimmed = inv.trim()
    val isAssetInvoice = trimmed.length > 20 && !trimmed.startsWith("ln", ignoreCase = true)

    androidx.compose.runtime.LaunchedEffect(inv) {
        // Reset everything derived from the previous invoice so a stale amount
        // or decode can never be paid against a newly-pasted invoice.
        decoded = null
        decodeError = null
        amountOverride = ""
        decoding = false
        payWithAsset = false
        if (trimmed.length > 20 && !isAssetInvoice) {
            decoding = true
            kotlinx.coroutines.delay(350) // debounce while pasting/typing
            runCatching { repository.requireNode().decode(trimmed) }
                .onSuccess { decoded = it }
                .onFailure { decodeError = it.message ?: "could not decode invoice" }
            decoding = false
        }
    }

    SheetTitle("Pay", onClose, badge = if (isAssetInvoice) "asset swap" else "xpay")
    DesignField(inv, { inv = it }, "lnbc…", mono = true, lines = 3)

    if (isAssetInvoice) {
        AssetInvoicePaySection(repository, trimmed, onClose)
        return
    }

    if (decoded == null && decodeError == null && inv.trim().length > 20) {
        Text(
            "Decoding…",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Dim,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    decodeError?.let {
        Text(
            "Can't decode: $it",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Red,
            modifier = Modifier.padding(top = 10.dp),
        )
    }
    decoded?.let { d ->
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp)
                .background(Tokens.Accent.copy(alpha = .05f), RoundedCornerShape(14.dp))
                .border(1.dp, Tokens.Accent.copy(alpha = .18f), RoundedCornerShape(14.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            DecRow("Amount", if (d.amountMsat != null) formatAmount(d.amountMsat, unit) + " " + unit.label else "any amount")
            DecRow("Description", d.description ?: d.offerDescription ?: "—")
            DecRow("Payee", shortId(d.payee, 10), mono = true)
            DecRow("Expires", expiresIn(d.createdAt?.let { c -> d.expiry?.let { e -> c + e } }), orange = true)
        }
        if (d.amountMsat == null) {
            FieldLabel("Amount to pay (${unit.label})", topPadding = 12)
            DesignField(amountOverride, { amountOverride = filterAmountInput(it, unit) }, "0", numeric = true, big = true)
        }
    }

    // Paying this L-BTC invoice with an issued asset (via the swap service):
    // offered once there's an issued asset to pay with at all.
    val hasIssuedAssets = availableAssets.orEmpty().any { it.id != "lbtc" }
    val lbtcWrapper = decoded?.let { wrapLbtcInvoice(inv.trim(), it) }
    if (decoded != null && hasIssuedAssets) {
        FieldLabel("Pay with", topPadding = 12)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            AssetPill("L-BTC", !payWithAsset) { payWithAsset = false }
            AssetPill("Another asset (swap)", payWithAsset) { if (lbtcWrapper != null) payWithAsset = true }
        }
        if (lbtcWrapper == null) {
            Text(
                "Paying with another asset needs a BOLT11 invoice with a fixed amount.",
                style = MaterialTheme.typography.bodySmall, color = Tokens.Faint,
                modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
    if (payWithAsset && lbtcWrapper != null) {
        AssetInvoicePaySection(repository, lbtcWrapper, onClose)
        return
    }

    Text(
        if (showAdv) "xpay options ▲" else "xpay options ▼",
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
        color = Tokens.Accent,
        modifier = Modifier.padding(top = 10.dp).noRipple { showAdv = !showAdv },
    )
    if (showAdv) {
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("maxfee (${unit.label})", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(maxFee, { maxFee = filterAmountInput(it, unit) }, "auto", numeric = true)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("retry_for (s)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(retryFor, { retryFor = it.filter { c -> c.isDigit() } }, "60", numeric = true)
            }
        }
        Row(modifier = Modifier.padding(top = 8.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("maxdelay", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(maxDelay, { maxDelay = it.filter { c -> c.isDigit() } }, "", numeric = true)
            }
            Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("partial_msat", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
                DesignField(partial, { partial = it.filter { c -> c.isDigit() } }, "", numeric = true)
            }
        }
        Column(modifier = Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("layers (askrene, comma-separated)", style = MaterialTheme.typography.bodySmall.copy(fontSize = 10.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Faint)
            DesignField(layers, { layers = it }, "", mono = true)
        }
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 12.dp)
            .background(Tokens.Orange.copy(alpha = .06f), RoundedCornerShape(12.dp))
            .border(1.dp, Tokens.Orange.copy(alpha = .2f), RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 10.dp),
    ) {
        Text(
            "Payment runs in the background — keep using the app while it routes.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, lineHeight = 17.sp),
            color = Tokens.OrangeSoft,
        )
    }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }

    val amountKnown = decoded?.amountMsat != null || parseAmountToMsat(amountOverride, unit) != null
    val payMsat = decoded?.amountMsat ?: parseAmountToMsat(amountOverride, unit)
    // Never pay until decode of the *current* invoice text has finished.
    val payEnabled = inv.isNotBlank() && decoded != null && !decoding && amountKnown
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(52.dp)
            .background(
                if (payEnabled) Tokens.Accent else Tokens.Accent.copy(alpha = .3f),
                RoundedCornerShape(999.dp),
            )
            .noRipple(enabled = payEnabled) {
                error = null
                val id = repository.startPayment(
                    PayCommand.Xpay(
                        XpayParams(
                            invstring = inv.trim(),
                            amountMsat = if (decoded?.amountMsat == null) parseAmountToMsat(amountOverride, unit) else null,
                            maxfeeMsat = parseAmountToMsat(maxFee, unit),
                            layers = layers.split(",").map { it.trim() }.filter { it.isNotEmpty() },
                            retryFor = retryFor.toUIntOrNull(),
                            partialMsat = partial.toULongOrNull(),
                            maxdelay = maxDelay.toUIntOrNull(),
                        ),
                    ),
                    description = decoded?.description ?: decoded?.offerDescription,
                )
                onPaymentStarted(id)
            },
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        androidx.compose.material3.Icon(
            androidx.compose.material.icons.Icons.Filled.Bolt, null,
            tint = Tokens.AccentInk,
            modifier = Modifier.size(15.dp),
        )
        Text(
            "Pay" + (payMsat?.let { " " + formatAmount(it, unit) + " " + unit.label } ?: ""),
            style = MaterialTheme.typography.labelLarge,
            color = Tokens.AccentInk,
            maxLines = 1,
            modifier = Modifier.padding(start = 6.dp),
        )
    }
}

@Composable
private fun DecRow(k: String, v: String, mono: Boolean = false, orange: Boolean = false) {
    Row(modifier = Modifier.fillMaxWidth()) {
        Text(k, style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp), color = Tokens.Faint)
        Text(
            v,
            style = MaterialTheme.typography.bodySmall.copy(
                fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
                fontFamily = if (mono) MonoFont else MaterialTheme.typography.bodySmall.fontFamily,
            ),
            color = if (orange) Tokens.Orange else Tokens.TextMid,
            modifier = Modifier.weight(1f),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
    }
}

// ─────────────────────────── Asset wallet ───────────────────────────

/**
 * The asset wallet's L-BTC, which pays the on-chain fees of asset sends and
 * asset channel opens, with a link to top it up.
 */
/**
 * Least asset-wallet L-BTC `assetchan-open` can work with: the channel's
 * fee reserve (assetchan-default-fee-reserve, 2 000 sat) plus the funding
 * fee (at least 1 000 sat, see assetchan.py's funding_fee) - both from one
 * explicit L-BTC output, else elementsd answers "Insufficient funds".
 */
private const val ASSET_CHANNEL_MIN_FEE_BALANCE_MSAT = 3_000_000uL

@Composable
private fun AssetFeeBalanceRow(
    repository: WalletRepository,
    /** Least fee balance the action needs, with why - null = just "more than 0". */
    required: Pair<ULong, String>? = null,
    onTopUp: () -> Unit,
) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val fee by repository.assetWalletLbtcMsat.collectAsState()
    val empty = (fee ?: 0uL) < (required?.first ?: 1uL)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .background(if (empty) Tokens.Orange.copy(alpha = .06f) else Tokens.Field, RoundedCornerShape(14.dp))
            .border(1.dp, if (empty) Tokens.Orange.copy(alpha = .3f) else Tokens.Border, RoundedCornerShape(14.dp))
            .padding(horizontal = 15.dp, vertical = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                "Fee balance (L-BTC)",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                color = Tokens.Dim, modifier = Modifier.weight(1f),
            )
            Text(
                "${formatAmount(fee, unit)} ${unit.label}",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 13.sp, fontFamily = MonoFont),
                color = if (empty) Tokens.Orange else Tokens.TextMid,
            )
            Text(
                "Top up",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp),
                color = Tokens.Accent,
                modifier = Modifier.padding(start = 12.dp).noRipple(onClick = onTopUp),
            )
        }
        if (empty) {
            Text(
                required?.second ?: "Liquid fees are paid in L-BTC from the asset wallet. Top it up before sending.",
                style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
                color = Tokens.Orange, modifier = Modifier.padding(top = 6.dp),
            )
        }
    }
}

@Composable
private fun SendAssetSheet(
    repository: WalletRepository,
    sheet: WalletSheet.SendAsset,
    onOpenSheet: (WalletSheet) -> Unit,
    onClose: () -> Unit,
) {
    val scope = rememberCoroutineScope()
    val balances by repository.assetBalances.collectAsState()
    val assets by repository.assets.collectAsState()
    val feeMsat by repository.assetWalletLbtcMsat.collectAsState()
    var addr by remember { mutableStateOf("") }
    var amount by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var txid by remember { mutableStateOf<String?>(null) }
    // Asset amounts are typed/shown in whole units ("0.5"), with the
    // asset's own precision.
    val precision = assets?.firstOrNull { it.id == sheet.assetId }?.precision?.toInt() ?: DEFAULT_ASSET_PRECISION
    val available = balances?.firstOrNull { it.asset == sheet.assetId }?.onchainBalanceMsat ?: 0uL
    val amountMsat = parseAssetAmountToMsat(amount, precision)

    SheetTitle("Send ${sheet.symbol}", onClose)
    txid?.let {
        Text("Broadcast ✓", style = MaterialTheme.typography.titleSmall, color = Tokens.Green)
        Text(
            it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp),
            color = Tokens.Dim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        PillButton("Done", modifier = Modifier.fillMaxWidth()) { onClose() }
        return
    }
    FieldLabel("Destination address")
    DesignField(addr, { addr = it }, "ex1q…", mono = true)
    FieldLabel("Amount (${sheet.symbol})", topPadding = 12)
    DesignField(amount, { amount = filterAssetAmountInput(it, precision) }, "0", numeric = true, big = true)
    Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
        Text(
            "Available: ${formatAssetUnits(available, precision)} ${sheet.symbol}",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
            modifier = Modifier.weight(1f),
        )
        Text(
            "Send max",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = Tokens.Accent,
            modifier = Modifier.noRipple { amount = formatAssetUnits(available, precision).replace(" ", "") },
        )
    }
    AssetFeeBalanceRow(repository) { onOpenSheet(WalletSheet.TopUpAssetWallet) }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    PillButton(
        if (busy) "Broadcasting…" else "Send on-chain",
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        enabled = !busy && addr.isNotBlank() && amountMsat != null && amountMsat > 0uL &&
            amountMsat <= available && (feeMsat ?: 0uL) > 0uL,
    ) {
        busy = true; error = null
        scope.launch {
            runCatching { repository.sendAsset(addr.trim(), sheet.assetId, amountMsat ?: 0uL) }
                .onSuccess { txid = it }.onFailure { error = it.message }
            busy = false
        }
    }
}

@Composable
private fun TopUpAssetWalletSheet(repository: WalletRepository, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    val funds by repository.funds.collectAsState()
    var amount by remember { mutableStateOf(formatAmount(5_000_000uL, unit).replace(" ", "")) }
    var feerate by remember { mutableStateOf("normal") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var txid by remember { mutableStateOf<String?>(null) }
    val available = funds?.onchainConfirmedMsat ?: 0uL
    val amountMsat = parseAmountToMsat(amount, unit)

    SheetTitle("Top up fee balance", onClose)
    txid?.let {
        Text("Sent ✓", style = MaterialTheme.typography.titleSmall, color = Tokens.Green)
        Text(
            "Usable for fees after 1 confirmation.",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Dim, modifier = Modifier.padding(top = 6.dp),
        )
        Text(
            it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp),
            color = Tokens.Dim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp),
        )
        PillButton("Done", modifier = Modifier.fillMaxWidth()) { onClose() }
        return
    }
    Text(
        "Assets live in a separate on-chain wallet, and Liquid fees are always paid in L-BTC. " +
            "This moves L-BTC there from your on-chain L-BTC balance.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 18.sp),
        color = Tokens.Dim,
    )
    AssetFeeBalanceRow(repository) {}
    FieldLabel("Amount (${unit.label})", topPadding = 14)
    DesignField(amount, { amount = filterAmountInput(it, unit) }, "0", numeric = true, big = true)
    Text(
        "Available on-chain: ${formatAmount(available, unit)} ${unit.label}",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
        modifier = Modifier.padding(top = 8.dp),
    )
    FieldLabel("Feerate", topPadding = 12)
    app.light.wallet.ui.FeerateChips(selected = feerate, onSelect = { feerate = it })
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    PillButton(
        if (busy) "Sending…" else "Top up",
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        enabled = !busy && amountMsat != null && amountMsat >= 1000uL && amountMsat <= available,
    ) {
        busy = true; error = null
        scope.launch {
            runCatching { repository.topUpAssetWallet((amountMsat ?: 0uL) / 1000uL, feerate) }
                .onSuccess { txid = it }.onFailure { error = it.message }
            busy = false
        }
    }
}

// ─────────────────────────── Open channel ───────────────────────────

@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
private fun OpenChannelSheet(
    repository: WalletRepository,
    initialAssetId: String?,
    onOpenSheet: (WalletSheet) -> Unit,
    onClose: () -> Unit,
) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    val assets by repository.assets.collectAsState()
    val assetBalances by repository.assetBalances.collectAsState()
    var amount by remember { mutableStateOf("") }
    var minconf by remember { mutableStateOf("") }
    var feerate by remember { mutableStateOf("") }
    var announce by remember { mutableStateOf(true) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<String?>(null) }
    // null = an ordinary L-BTC channel (fundchannel from CLN's wallet);
    // else an asset channel funded from the asset wallet (assetchan-open).
    // Either way dual-funded: the swap service matches the amount.
    var assetId by remember { mutableStateOf(initialAssetId) }

    // Channels are opened only with the swap service - there is no
    // free-form peer. Fetched once per sheet open; `null` after loading
    // means the server has none configured, and opening is disabled.
    var swapService by remember { mutableStateOf<app.light.wallet.core.SwapService?>(null) }
    var swapServiceLoaded by remember { mutableStateOf(false) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        swapService = runCatching { repository.getSwapService() }.getOrNull()
        swapServiceLoaded = true
    }

    // Assets that can fund a channel: the ones with an on-chain balance,
    // plus a preselected one even at 0 (the sheet then says why it can't).
    val symbols = remember(assets) { assets.orEmpty().associate { it.id to it.symbol } }
    val fundable = remember(assetBalances, initialAssetId) {
        val held = assetBalances.orEmpty().filter { it.asset != "lbtc" && it.onchainBalanceMsat > 0uL }.map { it.asset }
        (listOfNotNull(initialAssetId) + held).distinct()
    }
    val symbol = assetId?.let { symbols[it] ?: shortId(it, 4) }
    val assetAvailable = assetId?.let { id -> assetBalances?.firstOrNull { it.asset == id }?.onchainBalanceMsat } ?: 0uL

    SheetTitle(if (symbol != null) "Open $symbol channel" else "Open channel", onClose)
    result?.let {
        Text("Channel funding broadcast ✓", style = MaterialTheme.typography.titleSmall, color = Tokens.Green)
        Text(it, style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp), color = Tokens.Dim, modifier = Modifier.padding(top = 8.dp, bottom = 16.dp))
        PillButton("Done", modifier = Modifier.fillMaxWidth()) { onClose() }
        return
    }
    if (fundable.isNotEmpty()) {
        FieldLabel("Channel asset")
        androidx.compose.foundation.layout.FlowRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier = Modifier.padding(bottom = 14.dp),
        ) {
            AssetPill("L-BTC", assetId == null) { assetId = null; amount = "" }
            fundable.forEach { id ->
                AssetPill(symbols[id] ?: shortId(id, 4), assetId == id) { assetId = id; amount = "" }
            }
        }
    }
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.CardDeep, RoundedCornerShape(12.dp))
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text("Peer: swap service", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = Tokens.TextMid)
        Text(
            when {
                !swapServiceLoaded -> "Loading swap service…"
                swapService == null -> "Swap service is not configured server-side, so a channel can't be opened."
                else -> "A direct channel can only be opened with the swap service. " +
                    "It puts in as much as you do, so the channel holds twice your amount " +
                    "and you can receive right away."
            },
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp),
            color = if (swapServiceLoaded && swapService == null) Tokens.Red else Tokens.Faint,
        )
    }
    // Asset amounts are typed in whole units ("0.5"), with the asset's own precision.
    val assetPrecision = assetId?.let { id -> assets?.firstOrNull { it.id == id }?.precision?.toInt() } ?: DEFAULT_ASSET_PRECISION
    val amountMsat = if (assetId != null) parseAssetAmountToMsat(amount, assetPrecision) else parseAmountToMsat(amount, unit)
    Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text("Amount (${symbol ?: unit.label})", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Dim)
            DesignField(
                amount, {
                    amount = if (assetId != null) filterAssetAmountInput(it, assetPrecision) else filterAmountInput(it, unit)
                },
                when {
                    assetId != null -> "10"
                    unit == DisplayUnit.SAT -> "1 000 000"
                    unit == DisplayUnit.MSAT -> "1 000 000 000"
                    else -> "0.01"
                },
                numeric = true, big = true,
            )
        }
        // assetchan-open has no minconf/feerate: it builds and prices the
        // funding transaction itself.
        if (assetId == null) {
            Column(modifier = Modifier.width(110.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text("Min conf", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold), color = Tokens.Dim)
                DesignField(minconf, { minconf = it.filter { c -> c.isDigit() } }, "1", numeric = true)
            }
        }
    }
    if (assetId != null) {
        Text(
            "Available: ${formatAssetUnits(assetAvailable, assetPrecision)} $symbol",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
            modifier = Modifier.padding(top = 8.dp),
        )
        AssetFeeBalanceRow(
            repository,
            required = ASSET_CHANNEL_MIN_FEE_BALANCE_MSAT to
                "Opening an asset channel needs at least 3 000 sats of L-BTC here: 2 000 kept in the channel " +
                "as its fee reserve, plus the funding transaction's fee (1 000+). Top it up first.",
        ) { onOpenSheet(WalletSheet.TopUpAssetWallet) }
    } else {
        FieldLabel("Feerate", topPadding = 12)
        app.light.wallet.ui.FeerateChips(selected = feerate.ifBlank { "normal" }, onSelect = { feerate = it })
    }
    Row(
        modifier = Modifier.padding(top = 12.dp).noRipple { announce = !announce },
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        app.light.wallet.ui.DesignSwitch(checked = announce) { announce = !announce }
        Text("Announce", style = MaterialTheme.typography.labelMedium.copy(fontSize = 12.5.sp), color = Tokens.TextMid)
        Text("public channel, visible in gossip", style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp), color = Tokens.Faint)
    }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    val feeMsat by repository.assetWalletLbtcMsat.collectAsState()
    val amountOk = amountMsat != null && amountMsat > 0uL &&
        (assetId == null || (amountMsat <= assetAvailable && (feeMsat ?: 0uL) >= ASSET_CHANNEL_MIN_FEE_BALANCE_MSAT))
    PillButton(
        if (busy) "Opening…" else "Connect & fund channel",
        modifier = Modifier.fillMaxWidth().padding(top = 18.dp),
        enabled = !busy && swapService != null && amountOk,
    ) {
        val swap = swapService ?: return@PillButton
        busy = true; error = null
        val asset = assetId
        scope.launch {
            runCatching {
                val node = repository.requireNode()
                val nodeId = swap.nodeId.trim()
                val host = swap.addr.substringBeforeLast(":")
                val port = swap.addr.substringAfterLast(":", "").toUIntOrNull()
                node.connectPeer(nodeId, host, port)
                if (asset != null) {
                    repository.openAssetChannel(nodeId, asset, amountMsat ?: 0uL, announce).txid
                } else {
                    node.openChannel(
                        OpenChannelParams(
                            peerId = nodeId, amountSat = (amountMsat ?: 0uL) / 1000uL, all = false,
                            feerate = feerate.ifBlank { null }, announce = announce, pushMsat = null,
                            closeTo = null, minconf = minconf.toUIntOrNull(), mindepth = null, reserveSat = null,
                        ),
                    ).txid
                }
            }.onSuccess { result = "txid $it" }.onFailure { error = it.message }
            busy = false
        }
    }
    Text(
        if (assetId != null) "connect → assetchan-open (dual-funded)" else "connect → fundchannel (dual-funded)",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.sp, fontFamily = FontFamily.Monospace),
        color = Tokens.Faint,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
        modifier = Modifier.fillMaxWidth().padding(top = 10.dp),
    )
}

// ─────────────────────────── Details ───────────────────────────

@Composable
private fun InvoiceDetailSheet(repository: WalletRepository, sheet: WalletSheet.InvoiceDetail, onClose: () -> Unit) {
    val context = LocalContext.current
    val inv = sheet.invoice
    val unit = DisplayUnit.from(repository.displayUnit)
    val assets by repository.assets.collectAsState()
    SheetTitle("Invoice", onClose)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (inv.status == "unpaid" && inv.bolt11 != null) {
            QrBox(inv.bolt11!!.uppercase(), sizeDp = 164)
        }
        Text(
            app.light.wallet.ui.formatAmountIn(inv.amountMsat, inv.asset, assets, unit),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
            color = app.light.wallet.ui.statusColor(inv.status),
            modifier = Modifier.padding(vertical = 12.dp),
        )
    }
    KvPanel(
        listOfNotNull(
            Triple("Status", inv.status, false),
            Triple("Asset", inv.asset?.let { assetSymbolOf(it, assets) + " · " + it } ?: "L-BTC", inv.asset != null),
            Triple("Description", inv.description ?: "—", false),
            Triple("Label", inv.label, true),
            Triple("Payment hash", inv.paymentHash, true),
            inv.paymentPreimage?.let { Triple("Preimage", it, true) },
            Triple("Expires", formatUnixTime(inv.expiresAt), false),
            inv.paidAt?.let { Triple("Paid at", formatUnixTime(it), false) },
            inv.amountReceivedMsat?.let { Triple("Received", app.light.wallet.ui.formatAmountIn(it, inv.asset, assets, unit, full = true), false) },
        ),
    )
    inv.bolt11?.let {
        app.light.wallet.ui.OutlinePillButton("Copy bolt11", modifier = Modifier.fillMaxWidth().padding(top = 14.dp)) {
            copyToClipboard(context, "Invoice", it)
        }
    }
}

@Composable
private fun PaymentDetailSheet(repository: WalletRepository, sheet: WalletSheet.PaymentDetail, onClose: () -> Unit) {
    val p = sheet.pay
    val unit = DisplayUnit.from(repository.displayUnit)
    SheetTitle("Payment", onClose)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        Text(
            "−" + formatAmount(p.amountMsat, unit) + " " + unit.label,
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
            color = app.light.wallet.ui.statusColor(p.status),
            modifier = Modifier.padding(bottom = 12.dp),
        )
    }
    val fee = if (p.amountSentMsat != null && p.amountMsat != null &&
        p.amountSentMsat!! >= p.amountMsat!!
    ) p.amountSentMsat!! - p.amountMsat!! else null
    KvPanel(
        listOfNotNull(
            Triple("Status", p.status, false),
            Triple("Description", p.description?.ifBlank { null } ?: "—", !p.description.isNullOrBlank()),
            Triple("Destination", p.destination ?: "—", true),
            Triple("Fee", formatAmountFull(fee, unit), false),
            Triple("Created", formatUnixTime(p.createdAt), false),
            p.completedAt?.let { Triple("Completed", formatUnixTime(it), false) },
            Triple("Payment hash", p.paymentHash, true),
            p.preimage?.let { Triple("Preimage", it, true) },
            p.numberOfParts?.let { Triple("Parts", it.toString(), false) },
        ),
    )
}

@Composable
private fun ActivePaymentSheet(repository: WalletRepository, paymentId: Long, onClose: () -> Unit) {
    // Live: keeps rendering the latest state of this payment while it routes.
    val active by repository.activePayments.collectAsState()
    val unit = DisplayUnit.from(repository.displayUnit)
    val p = active.firstOrNull { it.id == paymentId }
    SheetTitle("Payment", onClose)
    if (p == null) {
        Text("Payment record not found.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim)
        return
    }
    val statusColor = when (p.status) {
        ActivePaymentStatus.PAYING -> Tokens.Orange
        ActivePaymentStatus.PENDING -> Tokens.Orange
        ActivePaymentStatus.COMPLETE -> Tokens.Green
        ActivePaymentStatus.FAILED -> Tokens.Red
    }
    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        modifier = Modifier.fillMaxWidth().padding(bottom = 16.dp),
    ) {
        if (p.status == ActivePaymentStatus.PAYING) {
            androidx.compose.material3.CircularProgressIndicator(
                color = Tokens.Orange, strokeWidth = 2.dp,
                modifier = Modifier.padding(bottom = 12.dp).size(28.dp),
            )
        }
        Text(
            p.amountMsat?.let { "−" + formatAmount(it, unit) + " " + unit.label }
                ?: when (p.status) {
                    ActivePaymentStatus.PAYING -> "Routing…"
                    ActivePaymentStatus.PENDING -> "Pending — still in flight"
                    ActivePaymentStatus.COMPLETE -> "Paid"
                    ActivePaymentStatus.FAILED -> "Failed"
                },
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
            color = statusColor,
        )
        Text(
            "via xpay" + if (p.status == ActivePaymentStatus.PAYING) " — routing…" else "",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
            modifier = Modifier.padding(top = 4.dp),
        )
    }
    KvPanel(
        listOfNotNull(
            Triple("Status", p.status.name.lowercase(), false),
            p.description?.let { Triple("Description", it, false) },
            Triple("Invoice", shortId(p.invstring, 20), true),
            p.feeMsat?.let { Triple("Fee", formatAmountFull(it, unit), false) },
            p.preimage?.let { Triple("Preimage", it, true) },
            p.error?.let { Triple("Error", it, false) },
        ),
        valueColor = { if (it == "Error") Tokens.Red else Tokens.TextMid },
    )
}

@Composable
private fun ChannelDetailSheet(
    repository: WalletRepository,
    sheet: WalletSheet.ChannelDetail,
    onClose: () -> Unit,
) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    val ch = sheet.channel
    val assets by repository.assets.collectAsState()
    // Asset channels: every amount is that asset's msat - show whole units
    // with its symbol instead of the L-BTC display unit.
    val assetId = ch.asset
    fun amountShort(msat: ULong?): String =
        if (assetId == null) formatAmount(msat, unit) + " " + unit.label
        else formatAssetUnits(msat, assetPrecisionOf(assetId, assets)) + " " + assetSymbolOf(assetId, assets)
    fun amountFull(msat: ULong?): String =
        if (assetId == null) formatAmountFull(msat, unit) else amountShort(msat)
    var confirmClose by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var closed by remember { mutableStateOf<String?>(null) }

    SheetTitle(ch.peerAlias?.takeIf { it.isNotBlank() } ?: "Channel", onClose)
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(bottom = 14.dp)) {
        Text(
            amountShort(ch.spendableMsat),
            style = MaterialTheme.typography.displaySmall.copy(fontSize = 30.sp),
        )
        Text(
            "spendable of ${amountShort(ch.totalMsat)} capacity",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 11.5.sp), color = Tokens.Faint,
            modifier = Modifier.padding(top = 3.dp),
        )
    }
    KvPanel(
        listOfNotNull(
            Triple("Peer", ch.peerId, true),
            Triple("State", ch.state, false),
            Triple("Connected", if (ch.peerConnected) "yes" else "no", false),
            ch.shortChannelId?.let { Triple("Short id", it, true) },
            assetId?.let { Triple("Asset", assetSymbolOf(it, assets) + " · " + it, true) },
            Triple("Capacity", amountFull(ch.totalMsat), false),
            Triple("Ours", amountFull(ch.toUsMsat), false),
            Triple("Theirs", amountFull(ch.toThemMsat), false),
            Triple("Spendable", amountFull(ch.spendableMsat), false),
            Triple("Receivable", amountFull(ch.receivableMsat), false),
            ch.ourReserveMsat?.let { Triple("Our reserve", amountFull(it), false) },
            ch.feeBaseMsat?.let { Triple("Fee base", amountFull(it), false) },
            ch.feeProportionalMillionths?.let { Triple("Fee ppm", it.toString(), false) },
            Triple("Opener", ch.opener, false),
            ch.fundingTxid?.let { Triple("Funding txid", it, true) },
        ),
    )
    closed?.let {
        Text("Close initiated: $it", color = Tokens.Green, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
        PillButton("Done", modifier = Modifier.fillMaxWidth().padding(top = 12.dp)) { onClose() }
        return
    }
    error?.let { Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 10.dp)) }
    DangerPillButton(
        when {
            busy -> "Closing…"
            confirmClose -> "Tap again to confirm close"
            else -> "Close channel"
        },
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        if (!confirmClose) { confirmClose = true; return@DangerPillButton }
        busy = true; error = null
        scope.launch {
            runCatching {
                repository.requireNode().closeChannel(
                    CloseChannelParams(
                        id = ch.channelId ?: ch.peerId,
                        unilateralTimeout = null, destination = null,
                        feeNegotiationStep = null, forceLeaseClosed = null,
                    ),
                )
            }.onSuccess {
                closed = it.closeType
                repository.refreshChannels(); repository.refreshAssetBalances(); repository.refreshFunds()
            }.onFailure { error = it.message }
            busy = false
        }
    }
}

// ─────────────────────────── Check route (askrene) ───────────────────────────

@Composable
private fun CheckRouteSheet(repository: WalletRepository, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val scope = rememberCoroutineScope()
    val connState by repository.connState.collectAsState()
    val ourId = (connState as? app.light.wallet.data.ConnState.Connected)?.info?.id

    var selfSource by remember { mutableStateOf(true) }
    var source by remember { mutableStateOf("") }
    var dest by remember { mutableStateOf("") }
    // Defaults mirror the design's prefills (converted into the display unit).
    var amount by remember { mutableStateOf(formatAmount(25_000_000uL, unit).replace(" ", "")) }
    var cltv by remember { mutableStateOf("18") }
    var maxFee by remember { mutableStateOf(formatAmount(250_000uL, unit).replace(" ", "")) }
    var maxDelay by remember { mutableStateOf("2016") }
    val layerOptions = listOf("auto.localchans", "auto.no_mpp_support", "auto.sourcefree", "xpay")
    var layers by remember { mutableStateOf(setOf("auto.localchans", "auto.no_mpp_support")) }
    var layerExtra by remember { mutableStateOf("") }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var result by remember { mutableStateOf<app.light.wallet.core.RoutesResult?>(null) }

    SheetTitle("Check route", onClose)

    FieldLabel("Source")
    if (selfSource) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .background(Tokens.Field, RoundedCornerShape(13.dp))
                .border(1.dp, Color(0x17FFFFFF), RoundedCornerShape(13.dp))
                .noRipple { selfSource = false }
                .padding(horizontal = 14.dp, vertical = 13.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                ourId?.let { shortId(it, 14) } ?: "—",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.5.sp),
                color = Tokens.TextMid, modifier = Modifier.weight(1f), maxLines = 1,
            )
            Text(
                "This node",
                style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp, fontWeight = FontWeight.Bold),
                color = Tokens.Accent,
            )
        }
    } else {
        DesignField(source, { source = it }, "Source node ID", mono = true, lines = 2)
        Text(
            "Use this node",
            style = MaterialTheme.typography.labelMedium.copy(fontSize = 11.5.sp),
            color = Tokens.Accent,
            modifier = Modifier.padding(top = 6.dp).noRipple { selfSource = true; source = "" },
        )
    }

    FieldLabel("Destination", topPadding = 12)
    DesignField(dest, { dest = it }, "Destination node ID", mono = true, lines = 2)

    Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel("Amount (${unit.label})", topPadding = 0)
            DesignField(amount, { amount = filterAmountInput(it, unit) }, "0", numeric = true)
        }
        Column(modifier = Modifier.width(118.dp)) {
            FieldLabel("Final CLTV", topPadding = 0)
            DesignField(cltv, { cltv = it.filter { c -> c.isDigit() } }, "18", numeric = true)
        }
    }
    Row(modifier = Modifier.padding(top = 12.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Column(modifier = Modifier.weight(1f)) {
            FieldLabel("Max fee (${unit.label})", topPadding = 0)
            DesignField(maxFee, { maxFee = filterAmountInput(it, unit) }, "0", numeric = true)
        }
        Column(modifier = Modifier.width(118.dp)) {
            FieldLabel("Max delay", topPadding = 0)
            DesignField(maxDelay, { maxDelay = it.filter { c -> c.isDigit() } }, "2016", numeric = true)
        }
    }

    FieldLabel("Layers", topPadding = 12)
    @OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
    androidx.compose.foundation.layout.FlowRow(
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        layerOptions.forEach { l ->
            val on = l in layers
            Text(
                l,
                style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFont, fontSize = 12.5.sp, fontWeight = FontWeight.Bold),
                color = if (on) Tokens.Accent else Tokens.Dim,
                modifier = Modifier
                    .background(if (on) Tokens.Accent.copy(alpha = .08f) else Color.Transparent, RoundedCornerShape(999.dp))
                    .border(1.dp, if (on) Tokens.Accent.copy(alpha = .4f) else Color(0x17FFFFFF), RoundedCornerShape(999.dp))
                    .noRipple { layers = if (on) layers - l else layers + l }
                    .padding(horizontal = 15.dp, vertical = 9.dp),
            )
        }
    }
    androidx.compose.foundation.layout.Spacer(Modifier.height(8.dp))
    DesignField(
        layerExtra, { layerExtra = it }, "Custom layer names, comma separated",
        mono = true,
    )

    val amountMsat = parseAmountToMsat(amount, unit)
    val maxFeeMsat = parseAmountToMsat(maxFee, unit)
    val ready = !busy && dest.isNotBlank() && amountMsat != null && maxFeeMsat != null &&
        (selfSource || source.isNotBlank())
    PillButton(
        if (busy) "Querying askrene…" else "Check route",
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
        enabled = ready, height = 52,
    ) {
        busy = true; error = null; result = null
        scope.launch {
            runCatching {
                repository.requireNode().getRoutes(
                    app.light.wallet.core.GetRoutesParams(
                        source = if (selfSource) null else source.trim(),
                        destination = dest.trim(),
                        amountMsat = amountMsat!!,
                        layers = (layers.toList() + layerExtra.split(",").map { it.trim() }.filter { it.isNotEmpty() }),
                        maxfeeMsat = maxFeeMsat!!,
                        finalCltv = cltv.toUIntOrNull(),
                        maxdelay = maxDelay.toUIntOrNull(),
                    ),
                )
            }.onSuccess { result = it }.onFailure { error = it.message ?: "getroutes failed" }
            busy = false
        }
    }

    error?.let {
        Text(it, color = Tokens.Red, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 12.dp))
    }
    result?.let { r ->
        val requested = amountMsat ?: 0uL
        val sent = r.routes.sumOf { rt -> (rt.path.firstOrNull()?.amountMsat ?: rt.amountMsat).toLong() }.toULong()
        val fee = if (sent > requested) sent - requested else 0uL
        Row(
            modifier = Modifier.fillMaxWidth().padding(top = 16.dp, bottom = 4.dp),
            verticalAlignment = Alignment.Bottom,
        ) {
            Text(
                "${r.routes.size} part${if (r.routes.size == 1) "" else "s"} found · " +
                    "probability ${"%.0f".format(java.util.Locale.US, r.probabilityPpm.toLong() / 10_000.0)}%",
                style = MaterialTheme.typography.bodySmall.copy(fontWeight = FontWeight.Bold),
                color = Tokens.Label, modifier = Modifier.weight(1f),
            )
            Text(
                formatAmountFull(fee, unit) + " fee",
                style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 13.sp),
                color = Tokens.Accent,
            )
        }
        r.routes.forEachIndexed { i, rt ->
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp)
                    .background(Tokens.Field, RoundedCornerShape(14.dp))
                    .border(1.dp, Color(0x12FFFFFF), RoundedCornerShape(14.dp))
                    .padding(horizontal = 14.dp, vertical = 13.dp),
                verticalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text(
                        "Part ${i + 1}",
                        style = MaterialTheme.typography.labelMedium.copy(fontFamily = MonoFont, fontSize = 12.sp, fontWeight = FontWeight.Bold),
                        color = Tokens.Accent,
                        modifier = Modifier
                            .background(Tokens.Accent.copy(alpha = .08f), RoundedCornerShape(999.dp))
                            .padding(horizontal = 10.dp, vertical = 3.dp),
                    )
                    Text(
                        "${rt.path.size} hop${if (rt.path.size == 1) "" else "s"} · delay ${rt.path.firstOrNull()?.delay ?: 0}",
                        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp),
                        color = Tokens.Dim, modifier = Modifier.weight(1f),
                    )
                    Text(
                        formatAmount(rt.amountMsat, unit),
                        style = MaterialTheme.typography.bodyMedium.copy(fontFamily = SpaceGrotesk, fontSize = 13.5.sp, fontWeight = FontWeight.SemiBold),
                    )
                }
                rt.path.forEachIndexed { hi, hop ->
                    val hopFee = if (hi + 1 < rt.path.size) hop.amountMsat - rt.path[hi + 1].amountMsat else 0uL
                    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        Text(
                            "${hi + 1}",
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 11.sp, fontWeight = FontWeight.SemiBold),
                            color = Tokens.Faint, modifier = Modifier.width(22.dp),
                        )
                        Text(
                            hop.shortChannelIdDir ?: shortId(hop.nextNodeId, 8),
                            style = MaterialTheme.typography.bodySmall.copy(fontFamily = MonoFont, fontSize = 12.sp),
                            color = Tokens.TextMid, modifier = Modifier.weight(1f), maxLines = 1,
                        )
                        Text(
                            formatAmountFull(hopFee, unit),
                            style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.sp),
                            color = Tokens.Dim,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun NodeSheet(repository: WalletRepository, onClose: () -> Unit) {
    val unit = DisplayUnit.from(repository.displayUnit)
    val context = LocalContext.current
    val connState by repository.connState.collectAsState()
    val info = (connState as? ConnState.Connected)?.info
    SheetTitle("Node", onClose)
    if (info == null) {
        Text("Node info is available once connected.", style = MaterialTheme.typography.bodyMedium, color = Tokens.Dim)
        return
    }
    KvPanel(
        listOf(
            Triple("Node ID", info.id, true),
            Triple("Alias", info.alias, false),
            Triple("Network", info.network, false),
            Triple("Block height", info.blockheight.toString(), false),
            Triple("Version", info.version, false),
            Triple("Peers", info.numPeers.toString(), false),
            Triple("Active channels", info.numActiveChannels.toString(), false),
            Triple("Connect string", info.connectString, true),
            Triple("Fees collected", formatAmountFull(info.feesCollectedMsat, unit), false),
        ),
    )
}

@Composable
private fun SeedSheet(repository: WalletRepository, onClose: () -> Unit) {
    val context = LocalContext.current
    var revealed by remember { mutableStateOf(false) }
    // StrongBox decrypt of the mnemonic — off-main, exactly once per open
    // (reading it in composition froze the frame for each recomposition).
    var words by remember { mutableStateOf<List<String>?>(null) }
    androidx.compose.runtime.LaunchedEffect(Unit) {
        words = repository.loadMnemonicWords()
    }
    val loaded = words
    SheetTitle("Recovery phrase", onClose)
    if (loaded == null) {
        Text(
            "Decrypting…",
            style = MaterialTheme.typography.bodySmall, color = Tokens.Dim,
            modifier = Modifier.padding(bottom = 14.dp),
        )
        return
    }
    Text(
        "Anyone with these ${loaded.size} words can take your funds. Reveal only in private.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 22.sp),
        color = Tokens.Label,
        modifier = Modifier.padding(bottom = 14.dp),
    )
    app.light.wallet.ui.SeedGrid(words = loaded, revealed = revealed, onReveal = { revealed = true })
    app.light.wallet.ui.OutlinePillButton(
        "Copy seed",
        modifier = Modifier.fillMaxWidth().padding(top = 14.dp),
    ) {
        copyToClipboard(context, "Seed", loaded.joinToString(" "), sensitive = true)
    }
    Text(
        "Reveal requires a private moment — a biometric gate lands before the seed is decrypted in a future release.",
        style = MaterialTheme.typography.bodySmall.copy(fontSize = 12.5.sp, lineHeight = 19.sp),
        color = Tokens.Dim,
        modifier = Modifier.padding(top = 12.dp),
    )
}

@Composable
private fun DropSheet(repository: WalletRepository, onCancel: () -> Unit = {}, onWiped: () -> Unit) {
    SheetTitle("Delete wallet data")
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .background(Tokens.Red.copy(alpha = .06f), RoundedCornerShape(14.dp))
            .border(1.dp, Tokens.Red.copy(alpha = .25f), RoundedCornerShape(14.dp))
            .padding(horizontal = 16.dp, vertical = 14.dp),
    ) {
        Text(
            "This wipes the seed, Greenlight certificates and local caches from this device. " +
                "Your node stays scheduled at Greenlight — recover it anytime with the 24 words.",
            style = MaterialTheme.typography.bodySmall.copy(fontSize = 14.sp, lineHeight = 23.sp),
            color = Color(0xFFF8B4B2),
        )
    }
    var armed by remember { mutableStateOf(false) }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 14.dp)
            .height(52.dp)
            .background(Tokens.Red, RoundedCornerShape(999.dp))
            .noRipple {
                if (!armed) armed = true
                else { repository.wipeWallet(); onWiped() }
            },
        contentAlignment = Alignment.Center,
    ) {
        Text(
            if (armed) "Tap again to erase" else "Erase wallet data",
            style = MaterialTheme.typography.labelLarge.copy(fontSize = 15.sp),
            color = Color(0xFF2B0705),
        )
    }
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp)
            .height(48.dp)
            .border(1.dp, Color(0x24FFFFFF), RoundedCornerShape(999.dp))
            .noRipple(onClick = onCancel),
        contentAlignment = Alignment.Center,
    ) {
        Text("Cancel", style = MaterialTheme.typography.labelMedium.copy(fontSize = 14.sp), color = Tokens.TextMid)
    }
}
