package app.auralynx.widget

import android.content.Context
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.glance.GlanceId
import androidx.glance.GlanceModifier
import androidx.glance.GlanceTheme
import androidx.glance.action.actionStartActivity
import androidx.glance.action.clickable
import androidx.glance.appwidget.GlanceAppWidget
import androidx.glance.appwidget.GlanceAppWidgetReceiver
import androidx.glance.appwidget.provideContent
import androidx.glance.background
import androidx.glance.layout.Alignment
import androidx.glance.layout.Column
import androidx.glance.layout.Row
import androidx.glance.layout.Spacer
import androidx.glance.layout.fillMaxSize
import androidx.glance.layout.padding
import androidx.glance.layout.width
import androidx.glance.text.FontWeight
import androidx.glance.text.Text
import androidx.glance.text.TextStyle
import app.auralynx.MainActivity
import app.auralynx.core.data.repo.DeviceRepository
import app.auralynx.core.data.repo.BatteryRepository
import dagger.hilt.EntryPoint
import dagger.hilt.InstallIn
import dagger.hilt.android.EntryPointAccessors
import dagger.hilt.components.SingletonComponent
import kotlinx.coroutines.flow.first
import java.text.DateFormat
import java.util.Date

@EntryPoint
@InstallIn(SingletonComponent::class)
interface WidgetEntryPoint {
    fun devices(): DeviceRepository
    fun battery(): BatteryRepository
}

/**
 * Home-screen widget showing the most recent *recorded* battery values with their timestamp.
 * It never extrapolates: when no measurement exists it says so.
 */
class BatteryWidget : GlanceAppWidget() {
    override suspend fun provideGlance(context: Context, id: GlanceId) {
        val ep = EntryPointAccessors.fromApplication(context, WidgetEntryPoint::class.java)
        val device = ep.devices().knownDevices.first().firstOrNull()
        val samples = device?.let { d ->
            listOf("LEFT", "RIGHT", "CASE", "SINGLE").mapNotNull { c -> ep.battery().latest(d.address, c)?.let { c to it } }
        }.orEmpty()
        provideContent {
            GlanceTheme {
                Column(
                    GlanceModifier.fillMaxSize().background(GlanceTheme.colors.widgetBackground).padding(14.dp)
                        .clickable(actionStartActivity<MainActivity>()),
                ) {
                    Text(device?.name ?: "Auralynx", style = TextStyle(fontWeight = FontWeight.Medium, fontSize = 14.sp, color = GlanceTheme.colors.onSurface))
                    Spacer(GlanceModifier.padding(top = 6.dp))
                    if (samples.isEmpty()) {
                        Text("No battery data yet", style = TextStyle(fontSize = 12.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    } else {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            samples.forEach { (c, s) ->
                                Column {
                                    Text("${s.level}%", style = TextStyle(fontSize = 20.sp, color = GlanceTheme.colors.onSurface))
                                    Text(c.lowercase().replaceFirstChar { it.uppercase() }, style = TextStyle(fontSize = 11.sp, color = GlanceTheme.colors.onSurfaceVariant))
                                }
                                Spacer(GlanceModifier.width(14.dp))
                            }
                        }
                        val time = samples.maxOf { it.second.timestampMillis }
                        Text("As of " + DateFormat.getTimeInstance(DateFormat.SHORT).format(Date(time)), style = TextStyle(fontSize = 10.sp, color = GlanceTheme.colors.onSurfaceVariant))
                    }
                }
            }
        }
    }
}

class BatteryWidgetReceiver : GlanceAppWidgetReceiver() {
    override val glanceAppWidget: GlanceAppWidget = BatteryWidget()
}
