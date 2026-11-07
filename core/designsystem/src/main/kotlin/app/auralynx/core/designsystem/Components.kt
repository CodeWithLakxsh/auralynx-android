package app.auralynx.core.designsystem

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.BluetoothDisabled
import androidx.compose.material.icons.outlined.Bolt
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Science
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.selection.toggleable

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, trailing: (@Composable () -> Unit)? = null) {
    Row(modifier.fillMaxWidth().padding(top = Tokens.lg, bottom = Tokens.sm), verticalAlignment = Alignment.CenterVertically) {
        Text(
            title.uppercase(), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.weight(1f).semantics { heading() },
        )
        trailing?.invoke()
    }
}

@Composable
fun AuralynxCard(modifier: Modifier = Modifier, content: @Composable ColumnScope.() -> Unit) {
    Card(
        modifier = modifier.fillMaxWidth(),
        shape = MaterialTheme.shapes.large,
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceContainerLow),
    ) { Column(Modifier.padding(Tokens.md), content = content) }
}

enum class StatusKind { LIVE, LIMITED, UNAVAILABLE, EXPERIMENTAL }

/** Coloured pill that always carries a text label (never colour alone). */
@Composable
fun StatusPill(label: String, kind: StatusKind, modifier: Modifier = Modifier) {
    val s = LocalStatusColors.current
    val color = when (kind) {
        StatusKind.LIVE -> s.live; StatusKind.LIMITED -> s.warning
        StatusKind.UNAVAILABLE -> s.unavailable; StatusKind.EXPERIMENTAL -> s.experimental
    }
    Surface(modifier, shape = CircleShape, color = color.copy(alpha = 0.14f)) {
        Row(Modifier.padding(horizontal = 10.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(8.dp).padding(0.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(color) } }
            Spacer(Modifier.width(6.dp))
            Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurface)
        }
    }
}

/**
 * Circular battery gauge. [level] null renders "--" (never a guessed value). [precisionNote]
 * states the source precision, e.g. "±10 % (Bluetooth advertisement)".
 */
@Composable
fun BatteryGauge(
    label: String,
    level: Int?,
    charging: Boolean,
    modifier: Modifier = Modifier,
    precisionNote: String? = null,
    diameter: androidx.compose.ui.unit.Dp = 88.dp,
) {
    val reduced = LocalReducedMotion.current
    val target = (level ?: 0) / 100f
    val progress by animateFloatAsState(target, if (reduced) tween(0) else tween(Tokens.motionLong), label = "battery")
    val track = MaterialTheme.colorScheme.surfaceContainerHighest
    val low = level != null && level <= 20 && !charging
    val arc = when {
        level == null -> MaterialTheme.colorScheme.outlineVariant
        low -> MaterialTheme.colorScheme.error
        charging -> LocalStatusColors.current.live
        else -> MaterialTheme.colorScheme.primary
    }
    val description = buildString {
        append(label).append(": ")
        append(if (level == null) "not reported" else "$level percent")
        if (charging) append(", charging")
        precisionNote?.let { append(", ").append(it) }
    }
    Column(modifier.clearAndSetSemantics { contentDescription = description }, horizontalAlignment = Alignment.CenterHorizontally) {
        Box(Modifier.size(diameter), contentAlignment = Alignment.Center) {
            Canvas(Modifier.fillMaxSize()) {
                val stroke = 7.dp.toPx()
                val inset = stroke / 2
                val sz = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 135f, 270f, false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
                if (level != null) drawArc(arc, 135f, 270f * progress, false, Offset(inset, inset), sz, style = Stroke(stroke, cap = StrokeCap.Round))
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (charging) Icon(Icons.Outlined.Bolt, null, Modifier.size(Tokens.iconSm), tint = LocalStatusColors.current.live)
                Text(
                    if (level == null) "--" else "$level%",
                    style = MaterialTheme.typography.titleLarge.merge(TabularNumbers), fontWeight = FontWeight.Medium,
                )
            }
        }
        Spacer(Modifier.height(Tokens.sm))
        Text(label, style = MaterialTheme.typography.labelLarge)
        precisionNote?.let { Text(it, style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center) }
    }
}

@Composable
fun SettingSwitch(
    title: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    enabled: Boolean = true,
) {
    ListItem(
        headlineContent = { Text(title) },
        supportingContent = supporting?.let { { Text(it) } },
        trailingContent = { Switch(checked = checked, onCheckedChange = null, enabled = enabled) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
        modifier = modifier.heightIn(min = Tokens.touchTarget)
            .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onCheckedChange),
    )
}

/** Explains why a feature is or is not available. */
@Composable
fun CapabilityRow(title: String, status: String, kind: StatusKind, explanation: String?, modifier: Modifier = Modifier) {
    ListItem(
        modifier = modifier,
        headlineContent = { Text(title) },
        supportingContent = explanation?.let { { Text(it, style = MaterialTheme.typography.bodySmall) } },
        trailingContent = { StatusPill(status, kind) },
        colors = ListItemDefaults.colors(containerColor = Color.Transparent),
    )
}

@Composable
fun ExperimentalBanner(text: String, modifier: Modifier = Modifier) {
    Surface(modifier.fillMaxWidth(), shape = MaterialTheme.shapes.medium, color = LocalStatusColors.current.experimental.copy(alpha = 0.12f)) {
        Row(Modifier.padding(Tokens.md), verticalAlignment = Alignment.Top) {
            Icon(Icons.Outlined.Science, null, tint = LocalStatusColors.current.experimental)
            Spacer(Modifier.width(Tokens.md))
            Text(text, style = MaterialTheme.typography.bodyMedium)
        }
    }
}

@Composable
fun InfoNote(text: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = Tokens.sm), verticalAlignment = Alignment.Top) {
        Icon(Icons.Outlined.Info, null, Modifier.size(Tokens.iconSm), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.width(Tokens.sm))
        Text(text, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Standard empty / disconnected / error / loading presentations. */
@Composable
fun StateMessage(
    icon: ImageVector,
    title: String,
    body: String,
    modifier: Modifier = Modifier,
    actionLabel: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(Tokens.xl), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(Tokens.sm)) {
        Surface(shape = CircleShape, color = MaterialTheme.colorScheme.surfaceContainerHigh, modifier = Modifier.size(72.dp)) {
            Box(contentAlignment = Alignment.Center) { Icon(icon, null, Modifier.size(Tokens.iconLg), tint = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
        Text(title, style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Text(body, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        if (actionLabel != null && onAction != null) {
            Spacer(Modifier.height(Tokens.sm))
            FilledTonalButton(onClick = onAction) { Text(actionLabel) }
        }
    }
}

@Composable
fun DisconnectedState(onFindDevices: () -> Unit, modifier: Modifier = Modifier) = StateMessage(
    Icons.Outlined.BluetoothDisabled, "No accessory connected",
    "Connect your AirPods or Beats in Android's Bluetooth settings. Auralynx shows only data your accessory actually reports.",
    modifier, "Find devices", onFindDevices,
)

@Composable
fun ErrorState(message: String, onRetry: (() -> Unit)?, modifier: Modifier = Modifier) =
    StateMessage(Icons.Outlined.ErrorOutline, "Something went wrong", message, modifier, if (onRetry != null) "Try again" else null, onRetry)

@Composable
fun LoadingState(label: String, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().padding(Tokens.xl).semantics { contentDescription = label }, horizontalAlignment = Alignment.CenterHorizontally) {
        CircularProgressIndicator()
        Spacer(Modifier.height(Tokens.md))
        Text(label, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** Label that marks a value as a calculated estimate rather than a measurement. */
@Composable
fun EstimateLabel(text: String = "Estimate", modifier: Modifier = Modifier) {
    Surface(modifier, shape = RoundedCornerShape(6.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
        Text(text, Modifier.padding(horizontal = 6.dp, vertical = 2.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSecondaryContainer)
    }
}

/**
 * Minimal line chart for time series (battery history). Points are (x in [0,1], y in [0,100]).
 * Screen readers get [summary] instead of the drawing.
 */
@Composable
fun LineChart(
    series: List<Pair<String, List<Pair<Float, Float>>>>,
    summary: String,
    modifier: Modifier = Modifier,
    colors: List<Color> = listOf(MaterialTheme.colorScheme.primary, MaterialTheme.colorScheme.secondary, MaterialTheme.colorScheme.tertiary),
) {
    val grid = MaterialTheme.colorScheme.outlineVariant
    Column(modifier) {
        Canvas(Modifier.fillMaxWidth().height(160.dp).clearAndSetSemantics { contentDescription = summary }) {
            listOf(0f, 0.5f, 1f).forEach { f ->
                val y = size.height * (1 - f)
                drawLine(grid, Offset(0f, y), Offset(size.width, y), strokeWidth = 1.dp.toPx())
            }
            series.forEachIndexed { i, (_, pts) ->
                if (pts.size < 2) return@forEachIndexed
                val path = Path()
                pts.forEachIndexed { j, (x, yv) ->
                    val px = x * size.width; val py = size.height * (1 - yv / 100f)
                    if (j == 0) path.moveTo(px, py) else path.lineTo(px, py)
                }
                drawPath(path, colors[i % colors.size], style = Stroke(2.5.dp.toPx(), cap = StrokeCap.Round))
            }
        }
        Row(Modifier.padding(top = Tokens.sm), horizontalArrangement = Arrangement.spacedBy(Tokens.md)) {
            series.forEachIndexed { i, (name, _) ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp)) { Canvas(Modifier.fillMaxSize()) { drawCircle(colors[i % colors.size]) } }
                    Spacer(Modifier.width(6.dp))
                    Text(name, style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}

@Composable
fun BarsVisualizer(values: FloatArray, modifier: Modifier = Modifier, minDb: Float = -70f) {
    val color = MaterialTheme.colorScheme.secondary
    Canvas(modifier.fillMaxWidth().height(64.dp).clearAndSetSemantics { contentDescription = "Output spectrum" }) {
        if (values.isEmpty()) return@Canvas
        val w = size.width / values.size
        values.forEachIndexed { i, db ->
            val h = ((db - minDb) / -minDb).coerceIn(0f, 1f) * size.height
            drawRoundRect(color, Offset(i * w + w * 0.15f, size.height - h), Size(w * 0.7f, h), androidx.compose.ui.geometry.CornerRadius(3f, 3f))
        }
    }
}

@Composable
fun LinkButton(text: String, modifier: Modifier = Modifier, onClick: () -> Unit) = TextButton(onClick, modifier.heightIn(min = Tokens.touchTarget)) { Text(text) }
