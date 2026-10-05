package io.github.deadeyebarb.tonearm.desktop.ui

import androidx.compose.foundation.shape.CutCornerShape
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private fun font(file: String, weight: FontWeight) = Font("tonearm/$file/$weight", { resource("fonts/$file") }, weight)

private fun resource(path: String): ByteArray =
    requireNotNull(Thread.currentThread().contextClassLoader.getResourceAsStream(path)) { "Missing resource $path" }.use { it.readBytes() }

val Orbitron = FontFamily(
    font("orbitron.ttf", FontWeight.Normal), font("orbitron.ttf", FontWeight.Medium), font("orbitron.ttf", FontWeight.SemiBold),
    font("orbitron.ttf", FontWeight.Bold), font("orbitron.ttf", FontWeight.Black),
)
val Rajdhani = FontFamily(
    font("rajdhani_regular.ttf", FontWeight.Normal), font("rajdhani_medium.ttf", FontWeight.Medium),
    font("rajdhani_semibold.ttf", FontWeight.SemiBold), font("rajdhani_bold.ttf", FontWeight.Bold),
)
val TechMono = FontFamily(font("share_tech_mono.ttf", FontWeight.Normal))

/** The same HUD palette as the phone: a near-black void, glassy panels and two neon accents. */
@Immutable
data class HudPalette(
    val accent: Color = Color(0xFF00E5FF),
    val accent2: Color = Color(0xFFFF2BD6),
    val void: Color = Color(0xFF04060B),
    val deep: Color = Color(0xFF070B14),
    val panel: Color = lerp(Color(0xFF0B1220), Color(0xFF00E5FF), 0.035f),
    val panelHigh: Color = lerp(Color(0xFF111B2E), Color(0xFF00E5FF), 0.05f),
    val line: Color = lerp(Color(0xFF1C2A44), Color(0xFF00E5FF), 0.12f),
    val text: Color = Color(0xFFE6F1FF),
    val dim: Color = Color(0xFF8399B8),
    val danger: Color = Color(0xFFFF4D6D),
    val ok: Color = Color(0xFF3DFFA2),
)

val LocalHud = staticCompositionLocalOf { HudPalette() }

object Hud {
    val colors: HudPalette
        @Composable get() = LocalHud.current
}

private val HudTypography = Typography(
    headlineLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 28.sp, lineHeight = 36.sp, letterSpacing = 0.5.sp),
    headlineMedium = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 24.sp, lineHeight = 32.sp, letterSpacing = 0.5.sp),
    headlineSmall = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.Bold, fontSize = 20.sp, lineHeight = 28.sp, letterSpacing = 0.5.sp),
    titleLarge = TextStyle(fontFamily = Orbitron, fontWeight = FontWeight.SemiBold, fontSize = 17.sp, lineHeight = 24.sp, letterSpacing = 1.5.sp),
    titleMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 18.sp, lineHeight = 24.sp, letterSpacing = 0.3.sp),
    titleSmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 0.5.sp),
    bodyLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 17.sp, lineHeight = 23.sp),
    bodyMedium = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 15.sp, lineHeight = 20.sp),
    bodySmall = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Medium, fontSize = 13.sp, lineHeight = 17.sp),
    labelLarge = TextStyle(fontFamily = Rajdhani, fontWeight = FontWeight.Bold, fontSize = 15.sp, lineHeight = 20.sp, letterSpacing = 1.2.sp),
    labelMedium = TextStyle(fontFamily = TechMono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.5.sp),
    labelSmall = TextStyle(fontFamily = TechMono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.5.sp),
)

/** Chamfered corners: opposite corners of a panel are cut, like a HUD frame. */
private val HudShapes = Shapes(
    extraSmall = CutCornerShape(3.dp),
    small = CutCornerShape(topStart = 6.dp, bottomEnd = 6.dp),
    medium = CutCornerShape(topStart = 10.dp, bottomEnd = 10.dp),
    large = CutCornerShape(topStart = 18.dp, bottomEnd = 18.dp),
    extraLarge = CutCornerShape(topStart = 22.dp, bottomEnd = 22.dp),
)

@Composable
fun TonearmTheme(content: @Composable () -> Unit) {
    val p = HudPalette()
    val scheme = darkColorScheme(
        primary = p.accent,
        onPrimary = Color(0xFF00080C),
        primaryContainer = p.accent.copy(alpha = 0.22f).compositeOver(p.panel),
        onPrimaryContainer = lerp(p.accent, Color.White, 0.55f),
        secondary = p.accent2,
        onSecondary = Color(0xFF12000E),
        background = p.void,
        onBackground = p.text,
        surface = p.deep,
        onSurface = p.text,
        surfaceVariant = p.panel,
        onSurfaceVariant = p.dim,
        surfaceContainerLowest = p.void,
        surfaceContainerLow = p.deep,
        surfaceContainer = p.panel,
        surfaceContainerHigh = p.panelHigh,
        surfaceContainerHighest = lerp(p.panelHigh, p.accent, 0.06f),
        outline = p.line,
        outlineVariant = p.line.copy(alpha = 0.6f),
        error = p.danger,
    )
    CompositionLocalProvider(LocalHud provides p) {
        MaterialTheme(colorScheme = scheme, typography = HudTypography, shapes = HudShapes) {
            // No Surface at the root, so text and icons need their default colour set here.
            CompositionLocalProvider(LocalContentColor provides p.text, content = content)
        }
    }
}
