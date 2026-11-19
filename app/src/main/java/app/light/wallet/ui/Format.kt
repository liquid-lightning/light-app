package app.light.wallet.ui

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Display units supported by Settings. */
enum class DisplayUnit(val label: String) {
    SAT("sats"), MSAT("msat"), BTC("L-BTC");

    companion object {
        fun from(stored: String): DisplayUnit = when (stored) {
            "btc" -> BTC
            "msat" -> MSAT
            else -> SAT
        }
    }
}

fun formatAmount(msat: ULong?, unit: DisplayUnit): String {
    if (msat == null) return "—"
    if (msat == 0uL) return "0" // zero is plain 0 in every unit and locale
    return when (unit) {
        DisplayUnit.SAT ->
            String.format(Locale.US, "%,d", (msat / 1000uL).toLong()).replace(',', ' ')
        DisplayUnit.MSAT ->
            String.format(Locale.US, "%,d", msat.toLong()).replace(',', ' ')
        DisplayUnit.BTC ->
            String.format(Locale.US, "%.8f", msat.toLong() / 100_000_000_000.0)
                .trimEnd('0').trimEnd('.')
    }
}

fun formatMsatAsSat(msat: ULong?): String =
    if (msat == null) "—"
    else String.format(Locale.US, "%,d sat", (msat / 1000uL).toLong()).replace(',', ' ')

/** Parse an amount the user typed in their display unit into msat. */
fun parseAmountToMsat(input: String, unit: DisplayUnit): ULong? {
    val t = input.trim().replace(" ", "")
    if (t.isEmpty()) return null
    return when (unit) {
        DisplayUnit.SAT -> t.toULongOrNull()?.let { it * 1000uL }
        DisplayUnit.MSAT -> t.toULongOrNull()
        DisplayUnit.BTC -> {
            val d = t.toBigDecimalOrNull() ?: return null
            if (d.signum() < 0) return null
            val msat = d.movePointRight(11) // 1 L-BTC = 1e11 msat
            if (msat.stripTrailingZeros().scale() > 0) return null // finer than 1 msat
            val bi = msat.toBigInteger()
            if (bi.bitLength() > 63) null else bi.toLong().toULong()
        }
    }
}

/** Input filter for an amount field: digits, plus a single dot in L-BTC mode. */
fun filterAmountInput(s: String, unit: DisplayUnit): String =
    if (unit == DisplayUnit.BTC) {
        val c = s.filter { it.isDigit() || it == '.' }
        val i = c.indexOf('.')
        if (i == -1) c else c.substring(0, i + 1) + c.substring(i + 1).replace(".", "")
    } else {
        s.filter { it.isDigit() }
    }

/**
 * Full-precision amount in the user's display unit, label included.
 * Sub-sat precision is never silently dropped: a 312 msat fee shows as
 * "0.312 sats", not "0 sats".
 */
fun formatAmountFull(msat: ULong?, unit: DisplayUnit): String {
    if (msat == null) return "—"
    val value = when (unit) {
        DisplayUnit.MSAT ->
            String.format(Locale.US, "%,d", msat.toLong()).replace(',', ' ')
        DisplayUnit.SAT -> {
            val whole = String.format(Locale.US, "%,d", (msat / 1000uL).toLong()).replace(',', ' ')
            val rem = (msat % 1000uL).toLong()
            if (rem == 0L) whole
            else whole + "." + String.format(Locale.US, "%03d", rem).trimEnd('0')
        }
        DisplayUnit.BTC ->
            String.format(Locale.US, "%.11f", msat.toLong() / 100_000_000_000.0)
                .trimEnd('0').trimEnd('.')
    }
    return "$value ${unit.label}"
}

// ── Issued assets ──────────────────────────────────────────────────────
// Asset amounts travel as msat (1000 msat per base unit, the same scale
// as L-BTC). One whole asset is 10^precision base units, so a balance is
// shown as base_units / 10^precision: 100_000_001 base units of an
// 8-decimal asset is "1.00000001".

/**
 * L-BTC's own asset id on Liquid mainnet and testnet. Older assetchan.py
 * builds only recognise L-BTC by elementsd's "bitcoin" label, which testnet
 * doesn't set, and report it by id - treat those ids as "lbtc".
 */
private val LBTC_ASSET_IDS = setOf(
    "6f0279e9ed041c3d710a9f57d0c02928416460c4b722ae3457a11eec381c526d", // mainnet
    "144c654344aa716d6f3abcc1ca90e5641e4e2a7f633bc09fe3baf64585819a49", // testnet
)

/** "lbtc" for L-BTC (by name or by its asset id), else the id unchanged. */
fun normalizeAssetId(id: String): String = if (id.lowercase() in LBTC_ASSET_IDS) "lbtc" else id

/** Precision assumed for an asset ember doesn't describe (Liquid's usual 8). */
const val DEFAULT_ASSET_PRECISION = 8

/**
 * Whole-asset amount with at most [precision] decimals (trailing zeros
 * dropped), integer part grouped like the other amounts: "1 234.5".
 * A sub-base-unit msat remainder is truncated, never rounded up.
 */
fun formatAssetUnits(msat: ULong?, precision: Int): String {
    if (msat == null) return "—"
    val plain = java.math.BigDecimal(java.math.BigInteger(msat.toString()))
        .movePointLeft(precision + 3)
        .setScale(precision, java.math.RoundingMode.DOWN)
        .stripTrailingZeros()
        .toPlainString()
    val dot = plain.indexOf('.')
    val int = if (dot == -1) plain else plain.substring(0, dot)
    val frac = if (dot == -1) "" else plain.substring(dot)
    return String.format(Locale.US, "%,d", java.math.BigInteger(int)).replace(',', ' ') + frac
}

/** Parse a whole-asset amount ("0.5") into msat; null if malformed or finer than one base unit. */
fun parseAssetAmountToMsat(input: String, precision: Int): ULong? {
    val t = input.trim().replace(" ", "")
    if (t.isEmpty()) return null
    val d = t.toBigDecimalOrNull() ?: return null
    if (d.signum() < 0) return null
    val base = d.movePointRight(precision)
    if (base.stripTrailingZeros().scale() > 0) return null // finer than one base unit
    val msat = base.toBigInteger().multiply(java.math.BigInteger.valueOf(1000))
    return if (msat.bitLength() > 64) null else msat.toString().toULong()
}

/** ember's description of issued asset [id] (ids compared case-insensitively), if it has one. */
fun assetInfoOf(id: String, assets: List<app.light.wallet.core.AssetInfo>?): app.light.wallet.core.AssetInfo? =
    assets?.firstOrNull { it.id.equals(id, ignoreCase = true) }

/** Symbol of issued asset [id]: ember's, else a shortened id. */
fun assetSymbolOf(id: String, assets: List<app.light.wallet.core.AssetInfo>?): String =
    assetInfoOf(id, assets)?.symbol ?: shortId(id, 4)

/** Precision of issued asset [id]: ember's, else [DEFAULT_ASSET_PRECISION]. */
fun assetPrecisionOf(id: String, assets: List<app.light.wallet.core.AssetInfo>?): Int =
    assetInfoOf(id, assets)?.precision?.toInt() ?: DEFAULT_ASSET_PRECISION

/**
 * An amount in [asset] (null or "lbtc" = L-BTC), with its unit:
 * "1 234 sats" / "0.5 USDt". [full] keeps sub-sat L-BTC precision.
 */
fun formatAmountIn(
    msat: ULong?,
    asset: String?,
    assets: List<app.light.wallet.core.AssetInfo>?,
    unit: DisplayUnit,
    full: Boolean = false,
): String =
    if (asset == null || asset == "lbtc") {
        if (full) formatAmountFull(msat, unit) else formatAmount(msat, unit) + " " + unit.label
    } else {
        formatAssetUnits(msat, assetPrecisionOf(asset, assets)) + " " + assetSymbolOf(asset, assets)
    }

/** Input filter for an asset amount: digits and one dot, at most [precision] decimals. */
fun filterAssetAmountInput(s: String, precision: Int): String {
    val c = s.filter { it.isDigit() || (precision > 0 && it == '.') }
    val i = c.indexOf('.')
    if (i == -1) return c
    return c.substring(0, i + 1) + c.substring(i + 1).replace(".", "").take(precision)
}

fun satsToBtcLine(msat: ULong?): String =
    if (msat == null) "" else String.format(Locale.US, "%.8f", msat.toLong() / 100_000_000_000.0)

/** Compact "830k" style used in liquidity labels. */
fun formatCompactSat(msat: ULong?): String {
    if (msat == null) return "—"
    val sat = (msat / 1000uL).toLong()
    return when {
        sat >= 100_000_000 -> String.format(Locale.US, "%.2f L-BTC", sat / 100_000_000.0)
        sat >= 1_000_000 -> String.format(Locale.US, "%.1fM", sat / 1_000_000.0)
        sat >= 1_000 -> String.format(Locale.US, "%,dk", sat / 1_000)
        else -> sat.toString()
    }
}

fun formatUnixTime(seconds: ULong?): String {
    if (seconds == null || seconds == 0uL) return "—"
    val fmt = SimpleDateFormat("MMM d, HH:mm", Locale.US)
    return fmt.format(Date(seconds.toLong() * 1000))
}

fun relativeTime(seconds: ULong?): String {
    if (seconds == null || seconds == 0uL) return "—"
    val diff = System.currentTimeMillis() / 1000 - seconds.toLong()
    return when {
        diff < 0 -> formatUnixTime(seconds)
        diff < 60 -> "just now"
        diff < 3600 -> "${diff / 60}m ago"
        diff < 86400 -> "${diff / 3600}h ago"
        diff < 7 * 86400 -> "${diff / 86400}d ago"
        else -> formatUnixTime(seconds)
    }
}

fun expiresIn(expiresAt: ULong?): String {
    if (expiresAt == null || expiresAt == 0uL) return "—"
    val diff = expiresAt.toLong() - System.currentTimeMillis() / 1000
    return when {
        diff <= 0 -> "expired"
        diff < 3600 -> "in ${diff / 60} min"
        diff < 86400 -> "in ${diff / 3600}h"
        else -> "in ${diff / 86400}d"
    }
}

fun shortId(id: String?, keep: Int = 10): String {
    if (id.isNullOrEmpty()) return "—"
    return if (id.length <= keep * 2) id else "${id.take(keep)}…${id.takeLast(keep)}"
}
