package cloud.pocket.panel.data

import java.text.NumberFormat
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.roundToLong

const val GB = 1024.0 * 1024 * 1024

fun bytes(b: Double): String {
    if (b < 1024) return "${b.roundToLong()} B"
    var v = b
    var i = -1
    val units = listOf("KB", "MB", "GB", "TB")
    do { v /= 1024; i++ } while (v >= 1024 && i < 3)
    val s = when {
        v >= 100 -> "%.0f".format(Locale.US, v)
        v >= 10 -> "%.1f".format(Locale.US, v)
        else -> "%.2f".format(Locale.US, v)
    }
    return "$s ${units[i]}"
}

fun bytes(b: Long) = bytes(b.toDouble())

/** Sizes of allocations and pool limits: GB from 1 GB up, otherwise the natural unit. */
fun gb(b: Double): String = if (b >= GB) "%s GB".format(Locale.US, (Math.round(b / GB * 100) / 100.0).toString().removeSuffix(".0")) else bytes(b)

fun count(n: Number): String = NumberFormat.getIntegerInstance(Locale.US).format(n)

private val dayTime = DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.UK)
private val timeOnly = DateTimeFormatter.ofPattern("HH:mm", Locale.UK)
private val dayOnly = DateTimeFormatter.ofPattern("EEE d MMM", Locale.UK)

fun dateTime(ts: Double): String = if (ts <= 0) "—" else dayTime.format(Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()))
fun clock(ts: Double): String = if (ts <= 0) "—" else timeOnly.format(Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()))
fun weekday(ts: Double): String = dayOnly.format(Instant.ofEpochMilli((ts * 1000).toLong()).atZone(ZoneId.systemDefault()))

fun ago(ts: Double): String {
    if (ts <= 0) return "never"
    val s = System.currentTimeMillis() / 1000.0 - ts
    return when {
        s < 90 -> "just now"
        s < 3600 -> "${(s / 60).roundToLong()} min ago"
        s < 86400 -> "${(s / 3600).roundToLong()} h ago"
        else -> "${(s / 86400).roundToLong()} days ago"
    }
}

fun duration(seconds: Double): String {
    val s = seconds.coerceAtLeast(0.0).toLong()
    val d = s / 86400
    val h = s % 86400 / 3600
    val m = s % 3600 / 60
    return when {
        d > 0 -> "$d d $h h"
        h > 0 -> "$h h $m min"
        else -> "$m min"
    }
}

fun pct(part: Double, whole: Double): Double = if (whole <= 0) 100.0 else part / whole * 100
