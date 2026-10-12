package cloud.pocket.panel.ui.components

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/** Round v to the step grid starting at min. */
private fun snap(v: Float, min: Float, step: Float): Float = min + ((v - min) / step).roundToInt() * step

private fun show(v: Float, step: Float): String =
    if (step >= 1f) v.roundToInt().toString() else ((v * 10).roundToInt() / 10.0).toString().removeSuffix(".0")

/**
 * A number setting: stock Material 3 slider with a number field beside it; moving either one moves the other.
 * With [none], the left end means "none" and the field shows empty.
 */
@Composable
fun SliderSetting(
    label: String, value: Float, min: Float, max: Float, unit: String,
    step: Float = 1f, hint: String? = null, none: Boolean = false,
    above: @Composable (() -> Unit)? = null, below: @Composable (() -> Unit)? = null, onChange: (Float) -> Unit,
) {
    val hi = if (max < min) min else max
    val v = value.coerceIn(min, hi)
    var text by remember(v) { mutableStateOf(if (none && v == min) "" else show(v, step)) }
    Column(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp)) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        if (hint != null) Text(hint, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f)) {
                val steps = ((hi - min) / step).roundToInt() - 1
                above?.invoke()
                Slider(value = v, onValueChange = { onChange(snap(it, min, step).coerceIn(min, hi)) }, valueRange = min..hi,
                    steps = if (steps in 1..199) steps else 0)
                below?.invoke()
            }
            OutlinedTextField(
                value = text,
                onValueChange = { t ->
                    text = t
                    if (t.isBlank() && none) onChange(min)
                    t.toFloatOrNull()?.let { onChange(snap(it.coerceIn(min, hi), min, step)) }
                },
                // A longer unit such as "% used" would squeeze the number out of the standard-width box.
                modifier = Modifier.width(if (unit.length > 3) 136.dp else 96.dp), singleLine = true,
                placeholder = { if (none) Text("none") },
                suffix = { Text(unit) },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
            )
        }
    }
}

/** A mark under the allocation slider. */
data class SliderMark(val value: Float, val label: String, val color: Color)

/**
 * Custom part, approved: small marks under a stock slider showing where the app's current use and its warning
 * point fall. Positions follow the slider's track (the stock slider's thumb is 20 dp wide, so the track starts
 * 10 dp in from each side). With [up], the row sits above the slider: label on top, tick pointing down at the track.
 */
@Composable
fun SliderMarks(marks: List<SliderMark>, min: Float, max: Float, up: Boolean = false) {
    val density = LocalDensity.current
    var width by remember { mutableStateOf(0) }
    Box(Modifier.fillMaxWidth().height(28.dp).onSizeChanged { width = it.width }) {
        if (width == 0) return@Box
        val pad = with(density) { 10.dp.toPx() }
        marks.forEach { m ->
            if (m.value < min || m.value > max) return@forEach
            val frac = if (max > min) (m.value - min) / (max - min) else 0f
            val x = pad + frac * (width - 2 * pad)
            MarkLabel(m, x, width.toFloat(), up)
        }
    }
}

@Composable
private fun MarkLabel(m: SliderMark, x: Float, total: Float, up: Boolean) {
    var labelWidth by remember { mutableStateOf(0) }
    val left = when {
        x - labelWidth / 2f < 0 -> 0f
        x + labelWidth / 2f > total -> total - labelWidth
        else -> x - labelWidth / 2f
    }
    Box(Modifier.offset { IntOffset(x.roundToInt() - 1, if (up) 22.dp.roundToPx() else 0) }.size(2.dp, 6.dp)
        .clip(RoundedCornerShape(1.dp)).background(m.color))
    Text(m.label, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.offset { IntOffset(left.roundToInt(), if (up) 0 else 8.dp.roundToPx()) }.onSizeChanged { labelWidth = it.width })
}

/** The allocation slider: whole GB steps of 0.5 between what the app holds and what the pool can still promise. */
@Composable
fun AllocationSlider(usedBytes: Double, maxBytes: Double, valueGb: Float, warnPct: Float, onChange: (Float) -> Unit) {
    val gb = 1024.0 * 1024 * 1024
    val min = maxOf(0.5f, (kotlin.math.ceil(usedBytes / gb * 2) / 2).toFloat())
    val max = maxOf(min, (kotlin.math.floor(maxBytes / gb * 2) / 2).toFloat())
    val warnAt = valueGb * warnPct / 100f
    SliderSetting(
        label = "Allocation", value = valueGb, min = min, max = max, unit = "GB", step = 0.5f,
        hint = "Shared and private spaces together · up to ${cloud.pocket.panel.data.gb(maxBytes)}",
        // Warning above the track and in-use below, as on the web panel, so the two labels never collide.
        above = if (warnAt < min) null else {
            { SliderMarks(listOf(SliderMark(warnAt, "warning", MaterialTheme.colorScheme.tertiary)), min, max, up = true) }
        },
        below = {
            SliderMarks(listOf(
                SliderMark((usedBytes / gb).toFloat().coerceAtLeast(min), "in use ${cloud.pocket.panel.data.bytes(usedBytes)}",
                    MaterialTheme.colorScheme.onSurfaceVariant),
            ), min, max)
        },
        onChange = onChange,
    )
}

/** Spacer helper used in rows. */
@Composable
fun HGap(w: Dp = 8.dp) = Box(Modifier.width(w))
