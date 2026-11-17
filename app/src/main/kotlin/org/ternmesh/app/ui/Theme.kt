// Tern's colours and type: the deep teal of the launcher icon and ternmesh.org, on cool neutrals,
// the same in every phone's app rather than taken from its wallpaper, so that Tern looks like Tern.
package org.ternmesh.app.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

private val Teal = Color(0xFF0B4F6C)
private val TealLight = Color(0xFF6CC3E6)

private val LightColors = lightColorScheme(
    primary = Teal,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFD7EAF2),
    onPrimaryContainer = Color(0xFF062B3B),
    secondary = Color(0xFF4A6573),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFE1EDF3),
    onSecondaryContainer = Color(0xFF0B2A38),
    tertiary = Color(0xFF7A4F9E),
    tertiaryContainer = Color(0xFFF1E6FA),
    onTertiaryContainer = Color(0xFF2E1240),
    error = Color(0xFFC2410C),
    errorContainer = Color(0xFFFDE5D8),
    onErrorContainer = Color(0xFF4A1503),
    background = Color.White,
    onBackground = Color(0xFF15212B),
    surface = Color.White,
    onSurface = Color(0xFF15212B),
    surfaceVariant = Color(0xFFEDF2F5),
    onSurfaceVariant = Color(0xFF5A6A77),
    surfaceContainerLowest = Color.White,
    surfaceContainerLow = Color(0xFFF7F9FA),
    surfaceContainer = Color(0xFFF2F5F7),
    surfaceContainerHigh = Color(0xFFECF1F4),
    surfaceContainerHighest = Color(0xFFE5EBEF),
    outline = Color(0xFFC3CDD4),
    outlineVariant = Color(0xFFDFE6EB),
)

private val DarkColors = darkColorScheme(
    primary = TealLight,
    onPrimary = Color(0xFF06212D),
    primaryContainer = Color(0xFF163A4B),
    onPrimaryContainer = Color(0xFFCDEBF7),
    secondary = Color(0xFFA9C2CF),
    onSecondary = Color(0xFF13303D),
    secondaryContainer = Color(0xFF163240),
    onSecondaryContainer = Color(0xFFD3E6EF),
    tertiary = Color(0xFFD3B4EE),
    tertiaryContainer = Color(0xFF3B2550),
    onTertiaryContainer = Color(0xFFF1E6FA),
    error = Color(0xFFF08A5D),
    errorContainer = Color(0xFF5A2410),
    onErrorContainer = Color(0xFFFDE5D8),
    background = Color(0xFF0E1418),
    onBackground = Color(0xFFE6EBEF),
    surface = Color(0xFF0E1418),
    onSurface = Color(0xFFE6EBEF),
    surfaceVariant = Color(0xFF1C2930),
    onSurfaceVariant = Color(0xFF93A3AF),
    surfaceContainerLowest = Color(0xFF0A0F12),
    surfaceContainerLow = Color(0xFF121B21),
    surfaceContainer = Color(0xFF162027),
    surfaceContainerHigh = Color(0xFF1B262D),
    surfaceContainerHighest = Color(0xFF213039),
    outline = Color(0xFF3A4A55),
    outlineVariant = Color(0xFF24323B),
)

/** Colours outside Material's scheme: a state that is fine, one that wants attention, the hero card's, and the user's own messages. */
@Immutable
data class TernColors(
    val good: Color,
    val warn: Color,
    val heroTop: Color,
    val heroBottom: Color,
    val onHero: Color,
    val mine: Color,
    val onMine: Color,
)

private val LightExtra = TernColors(good = Color(0xFF1F8A5B), warn = Color(0xFFB7791F), heroTop = Teal, heroBottom = Color(0xFF07364A), onHero = Color.White, mine = Teal, onMine = Color.White)
private val DarkExtra = TernColors(good = Color(0xFF4CC38A), warn = Color(0xFFE0A84A), heroTop = Color(0xFF1C5F7D), heroBottom = Color(0xFF103C50), onHero = Color.White, mine = Color(0xFF1F6F92), onMine = Color.White)

val LocalTernColors = staticCompositionLocalOf { LightExtra }

private val base = Typography()

/** Material's type, with headings a little heavier and tighter so that a screen's title leads it. */
private val TernTypography = base.copy(
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, letterSpacing = (-0.25).sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    labelLarge = base.labelLarge.copy(fontWeight = FontWeight.SemiBold),
)

/** The small, spaced capitals over a group of rows. */
val SectionLabel = TextStyle(fontSize = 12.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 0.8.sp)

private val TernShapes = Shapes(
    extraSmall = RoundedCornerShape(6.dp),
    small = RoundedCornerShape(10.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun TernTheme(dark: Boolean = isSystemInDarkTheme(), content: @Composable () -> Unit) {
    androidx.compose.runtime.CompositionLocalProvider(LocalTernColors provides if (dark) DarkExtra else LightExtra) {
        MaterialTheme(
            colorScheme = if (dark) DarkColors else LightColors,
            typography = TernTypography,
            shapes = TernShapes,
            content = content,
        )
    }
}
