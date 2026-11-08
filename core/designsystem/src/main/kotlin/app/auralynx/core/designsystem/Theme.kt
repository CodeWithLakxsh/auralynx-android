package app.auralynx.core.designsystem

import android.os.Build
import android.provider.Settings
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.ColorScheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Auralynx visual language: warm stone neutrals, a restrained brass accent, and a teal "live" hue
 * reserved for connection state. All text/background pairs below meet WCAG AA (4.5:1).
 */
private object Palette {
    val Stone950 = Color(0xFF0C0A09)
    val Stone900 = Color(0xFF1C1917)
    val Stone850 = Color(0xFF24201D)
    val Stone800 = Color(0xFF292524)
    val Stone700 = Color(0xFF44403C)
    val Stone500 = Color(0xFF78716C)
    val Stone400 = Color(0xFFA8A29E)
    val Stone300 = Color(0xFFD6D3D1)
    val Stone200 = Color(0xFFE7E5E4)
    val Stone100 = Color(0xFFF5F5F4)
    val Stone50 = Color(0xFFFAFAF9)
    val Brass700 = Color(0xFFA16207)
    val Brass300 = Color(0xFFE3B04B)
    val Brass900 = Color(0xFF3F2A05)
    val Brass100 = Color(0xFFFBEFD3)
    val Teal700 = Color(0xFF0F766E)
    val Teal300 = Color(0xFF5EEAD4)
    val Teal950 = Color(0xFF042F2E)
    val Teal100 = Color(0xFFCCFBF1)
    val Red600 = Color(0xFFDC2626)
    val Red300 = Color(0xFFFCA5A5)
}

private val LightColors = lightColorScheme(
    primary = Palette.Stone900, onPrimary = Color.White,
    primaryContainer = Palette.Stone200, onPrimaryContainer = Palette.Stone950,
    secondary = Palette.Brass700, onSecondary = Color.White,
    secondaryContainer = Palette.Brass100, onSecondaryContainer = Palette.Brass900,
    tertiary = Palette.Teal700, onTertiary = Color.White,
    tertiaryContainer = Palette.Teal100, onTertiaryContainer = Palette.Teal950,
    background = Palette.Stone50, onBackground = Palette.Stone950,
    surface = Palette.Stone50, onSurface = Palette.Stone950,
    surfaceVariant = Palette.Stone200, onSurfaceVariant = Palette.Stone700,
    surfaceContainerLowest = Color.White, surfaceContainerLow = Palette.Stone100,
    surfaceContainer = Palette.Stone100, surfaceContainerHigh = Palette.Stone200, surfaceContainerHighest = Palette.Stone300,
    outline = Palette.Stone500, outlineVariant = Palette.Stone300,
    error = Palette.Red600, onError = Color.White,
)

private val DarkColors = darkColorScheme(
    primary = Palette.Stone100, onPrimary = Palette.Stone900,
    primaryContainer = Palette.Stone800, onPrimaryContainer = Palette.Stone100,
    secondary = Palette.Brass300, onSecondary = Palette.Brass900,
    secondaryContainer = Color(0xFF4A3509), onSecondaryContainer = Palette.Brass100,
    tertiary = Palette.Teal300, onTertiary = Palette.Teal950,
    tertiaryContainer = Color(0xFF134E4A), onTertiaryContainer = Palette.Teal100,
    background = Palette.Stone950, onBackground = Palette.Stone100,
    surface = Palette.Stone950, onSurface = Palette.Stone100,
    surfaceVariant = Palette.Stone800, onSurfaceVariant = Palette.Stone300,
    surfaceContainerLowest = Color.Black, surfaceContainerLow = Palette.Stone900,
    surfaceContainer = Palette.Stone900, surfaceContainerHigh = Palette.Stone850, surfaceContainerHighest = Palette.Stone800,
    outline = Palette.Stone400, outlineVariant = Palette.Stone700,
    error = Palette.Red300, onError = Palette.Stone950,
)

private val AuralynxTypography = Typography().run {
    copy(
        displaySmall = displaySmall.copy(fontWeight = FontWeight.Light, letterSpacing = (-0.5).sp),
        headlineMedium = headlineMedium.copy(fontWeight = FontWeight.Normal, letterSpacing = (-0.25).sp),
        headlineSmall = headlineSmall.copy(fontWeight = FontWeight.Normal),
        titleLarge = titleLarge.copy(fontWeight = FontWeight.Medium),
        titleMedium = titleMedium.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.1.sp),
        labelLarge = labelLarge.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.2.sp),
        labelSmall = labelSmall.copy(fontWeight = FontWeight.Medium, letterSpacing = 0.6.sp),
    )
}

private val AuralynxShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(24.dp),
    extraLarge = RoundedCornerShape(32.dp),
)

/** Spacing rhythm (4/8 dp), icon sizes and motion durations as tokens. */
@Immutable
object Tokens {
    val xs = 4.dp; val sm = 8.dp; val md = 16.dp; val lg = 24.dp; val xl = 32.dp; val xxl = 48.dp
    val gutter = 20.dp
    val iconSm = 18.dp; val iconMd = 24.dp; val iconLg = 32.dp
    val touchTarget = 48.dp
    /** Readable content width on tablets and landscape phones. */
    val maxContentWidth = 720.dp
    const val motionShort = 150
    const val motionMedium = 280
    const val motionLong = 450
}

/** Semantic status colours. Status is always paired with text/icon, never colour alone. */
@Immutable
data class StatusColors(val live: Color, val onLive: Color, val warning: Color, val unavailable: Color, val experimental: Color)

val LocalStatusColors = staticCompositionLocalOf { StatusColors(Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified, Color.Unspecified) }

/** True when the user disabled animations system-wide; components then render final states directly. */
val LocalReducedMotion = staticCompositionLocalOf { false }

@Composable
fun AuralynxTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    dynamicColor: Boolean = true,
    content: @Composable () -> Unit,
) {
    val context = LocalContext.current
    val scheme: ColorScheme = when {
        dynamicColor && Build.VERSION.SDK_INT >= 31 -> if (darkTheme) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        darkTheme -> DarkColors
        else -> LightColors
    }
    val status = if (darkTheme) {
        StatusColors(Palette.Teal300, Palette.Teal950, Palette.Brass300, Palette.Stone400, Color(0xFFC4B5FD))
    } else {
        StatusColors(Palette.Teal700, Color.White, Palette.Brass700, Palette.Stone500, Color(0xFF6D28D9))
    }
    val reducedMotion = remember {
        runCatching { Settings.Global.getFloat(context.contentResolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f) == 0f }.getOrDefault(false)
    }
    CompositionLocalProvider(LocalStatusColors provides status, LocalReducedMotion provides reducedMotion) {
        MaterialTheme(colorScheme = scheme, typography = AuralynxTypography, shapes = AuralynxShapes, content = content)
    }
}

val TabularNumbers: TextStyle = TextStyle(fontFeatureSettings = "tnum")
