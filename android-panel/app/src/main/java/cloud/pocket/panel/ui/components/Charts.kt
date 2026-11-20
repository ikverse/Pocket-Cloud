package cloud.pocket.panel.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.background
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.ui.theme.PocketTheme
import com.patrykandpatrick.vico.compose.cartesian.CartesianChartHost
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberBottom
import com.patrykandpatrick.vico.compose.cartesian.axis.rememberStart
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLine
import com.patrykandpatrick.vico.compose.cartesian.layer.rememberLineCartesianLayer
import com.patrykandpatrick.vico.compose.cartesian.marker.rememberDefaultCartesianMarker
import com.patrykandpatrick.vico.compose.cartesian.rememberCartesianChart
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoScrollState
import com.patrykandpatrick.vico.compose.cartesian.rememberVicoZoomState
import com.patrykandpatrick.vico.compose.common.ProvideVicoTheme
import com.patrykandpatrick.vico.compose.common.component.rememberTextComponent
import com.patrykandpatrick.vico.compose.common.fill
import com.patrykandpatrick.vico.compose.m3.common.rememberM3VicoTheme
import com.patrykandpatrick.vico.core.cartesian.Zoom
import com.patrykandpatrick.vico.core.cartesian.axis.HorizontalAxis
import com.patrykandpatrick.vico.core.cartesian.axis.VerticalAxis
import com.patrykandpatrick.vico.core.cartesian.data.CartesianChartModelProducer
import com.patrykandpatrick.vico.core.cartesian.data.CartesianValueFormatter
import com.patrykandpatrick.vico.core.cartesian.data.lineSeries
import com.patrykandpatrick.vico.core.cartesian.layer.LineCartesianLayer
import kotlin.math.roundToInt

/** One line of a chart. */
data class Series(val label: String, val values: List<Double>, val color: Color)

/** Colours for chart lines: the brand colour first, then warn, soft and bad (brand.md section 2), then the rest. */
@Composable
fun seriesColors(): List<Color> = PocketTheme.extra.let { x ->
    MaterialTheme.colorScheme.run { listOf(x.brand, x.warn, onSurfaceVariant, x.bad, x.ok, x.faint, onSurface, inversePrimary) }
}

/**
 * Custom part, approved: a line chart drawn with the Vico library (Material 3 has no charts), in theme colours,
 * with a touch read-out. [xLabel] turns an x index into its axis label.
 */
@Composable
fun LineChart(series: List<Series>, xLabel: (Int) -> String, yLabel: (Double) -> String = { it.roundToInt().toString() },
              modifier: Modifier = Modifier) {
    if (series.isEmpty() || series.all { it.values.isEmpty() }) return
    val producer = remember { CartesianChartModelProducer() }
    LaunchedEffect(series) {
        producer.runTransaction {
            lineSeries { series.forEach { s -> series(s.values.indices.toList(), s.values) } }
        }
    }
    val text = rememberTextComponent(color = MaterialTheme.colorScheme.onSurface)
    // About six time labels, so they don't overlap on a phone; touching the chart shows every point.
    val labelStep = (series.maxOf { it.values.size } + 5) / 6
    ProvideVicoTheme(rememberM3VicoTheme()) {
        CartesianChartHost(
            chart = rememberCartesianChart(
                rememberLineCartesianLayer(
                    LineCartesianLayer.LineProvider.series(series.map {
                        LineCartesianLayer.rememberLine(fill = LineCartesianLayer.LineFill.single(fill(it.color)), areaFill = null)
                    })
                ),
                startAxis = VerticalAxis.rememberStart(valueFormatter = CartesianValueFormatter { _, v, _ -> yLabel(v) }),
                bottomAxis = HorizontalAxis.rememberBottom(
                    valueFormatter = CartesianValueFormatter { _, v, _ -> xLabel(v.roundToInt()) },
                    itemPlacer = remember(labelStep) { HorizontalAxis.ItemPlacer.aligned(spacing = { labelStep }) },
                ),
                marker = rememberDefaultCartesianMarker(label = text),
            ),
            modelProducer = producer,
            // The whole period fits the card, like the web panel; sideways scrolling would hide the newest points.
            scrollState = rememberVicoScrollState(scrollEnabled = false),
            zoomState = rememberVicoZoomState(zoomEnabled = false, initialZoom = Zoom.Content),
            modifier = modifier.fillMaxWidth().height(200.dp),
        )
    }
    Legend(series.map { it.label to it.color })
}

@Composable
fun Legend(items: List<Pair<String, Color>>) {
    FlowRow(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        items.forEach { (label, color) ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(9.dp).clip(RoundedCornerShape(50)).background(color))
                Text(" $label", style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** A part of the storage split bar. */
data class Part(val label: String, val bytes: Double, val color: Color)

/**
 * Custom part, approved: one bar split into what fills a storage (in theme colours), with a line where
 * Pocket's pool limit sits. A stock progress bar can only show one value.
 */
@Composable
fun StorageSplitBar(parts: List<Part>, total: Double, limitAt: Double?, modifier: Modifier = Modifier) {
    val track = MaterialTheme.colorScheme.surfaceContainer
    val line = MaterialTheme.colorScheme.onSurface
    Canvas(modifier.fillMaxWidth().height(20.dp).padding(vertical = 5.dp)) {
        val r = CornerRadius(size.height / 2)
        drawRoundRect(track, size = size, cornerRadius = r)
        val pill = androidx.compose.ui.graphics.Path().apply { addRoundRect(androidx.compose.ui.geometry.RoundRect(0f, 0f, size.width, size.height, r)) }
        var x = 0f
        clipPath(pill) {
            parts.forEach { p ->
                val w = (p.bytes / total * size.width).toFloat().coerceAtLeast(0f)
                drawRect(p.color, topLeft = Offset(x, 0f), size = Size(w.coerceAtMost(size.width - x), size.height))
                x += w
            }
        }
        limitAt?.let {
            val lx = (it / total * size.width).toFloat().coerceIn(1f, size.width - 1f)
            drawRect(line, topLeft = Offset(lx - 1.dp.toPx(), -3.dp.toPx()), size = Size(2.dp.toPx(), size.height + 6.dp.toPx()))
        }
    }
}
