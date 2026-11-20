package cloud.pocket.panel.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.RowScope
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import cloud.pocket.panel.ui.theme.ButtonShape
import cloud.pocket.panel.ui.theme.SmallButtonShape

// The mockups' buttons (brand.md section 5): 12 dp corners (9 when small), not Material's pills.
// Same names and main parameters as Material's, so screens only change their import.

/** The filled, primary button: accent with accent-ink text. */
@Composable
fun Button(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
           content: @Composable RowScope.() -> Unit) {
    androidx.compose.material3.Button(onClick, modifier, enabled, shape = if (small) SmallButtonShape else ButtonShape,
        contentPadding = padding(small)) {
        ProvideTextStyle(style(small)) { content() }
    }
}

/** The plain button: surface colour, 1 dp line border, ink text (bad text when [danger]). */
@Composable
fun OutlinedButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true, small: Boolean = false,
                   danger: Boolean = false, content: @Composable RowScope.() -> Unit) {
    val cs = MaterialTheme.colorScheme
    androidx.compose.material3.OutlinedButton(onClick, modifier, enabled, shape = if (small) SmallButtonShape else ButtonShape,
        colors = ButtonDefaults.outlinedButtonColors(containerColor = cs.surfaceContainerLow,
            contentColor = if (danger) cs.error else cs.onSurface),
        border = BorderStroke(1.dp, cs.outlineVariant), contentPadding = padding(small)) {
        ProvideTextStyle(style(small)) { content() }
    }
}

private fun padding(small: Boolean) = if (small) PaddingValues(horizontal = 12.dp, vertical = 4.dp) else PaddingValues(horizontal = 16.dp, vertical = 8.dp)

@Composable
private fun style(small: Boolean) =
    (if (small) MaterialTheme.typography.labelMedium else MaterialTheme.typography.labelLarge).copy(fontWeight = androidx.compose.ui.text.font.FontWeight(500))
