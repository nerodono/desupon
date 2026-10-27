package moe.desupon.ui.theme

import androidx.compose.material3.ColorScheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.ui.graphics.Color

// MCU 0.3.0 TONAL_SPOT, spec 2021: primary H158/C44, secondary H160/C18, tertiary H52/C28 (chestnut, not ruby: ruby tertiary collides with error), neutral H158/C4, neutralVariant H158/C8.
internal val DarkColors = darkColorScheme(
    primary = Color(0xFF84D8A1), onPrimary = Color(0xFF00391E),
    primaryContainer = Color(0xFF00522D), onPrimaryContainer = Color(0xFF9FF5BB),
    inversePrimary = Color(0xFF0E6C3F), secondary = Color(0xFFB2CDB9),
    onSecondary = Color(0xFF1E3527), secondaryContainer = Color(0xFF344C3D),
    onSecondaryContainer = Color(0xFFCEE9D4), tertiary = Color(0xFFF8B991),
    onTertiary = Color(0xFF4D2609), tertiaryContainer = Color(0xFF673C1E),
    onTertiaryContainer = Color(0xFFFFDBC7), error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005), errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6), background = Color(0xFF111411),
    onBackground = Color(0xFFE1E3DE), surface = Color(0xFF111411),
    onSurface = Color(0xFFE1E3DE), surfaceVariant = Color(0xFF414942),
    onSurfaceVariant = Color(0xFFC0C9C0), surfaceTint = Color(0xFF84D8A1),
    inverseSurface = Color(0xFFE1E3DE), inverseOnSurface = Color(0xFF2E312E),
    outline = Color(0xFF8A938B), outlineVariant = Color(0xFF414942),
    scrim = Color(0xFF000000), surfaceBright = Color(0xFF373A37),
    surfaceDim = Color(0xFF111411), surfaceContainer = Color(0xFF1D201D),
    surfaceContainerHigh = Color(0xFF282B28), surfaceContainerHighest = Color(0xFF333532),
    surfaceContainerLow = Color(0xFF191C1A), surfaceContainerLowest = Color(0xFF0C0F0C),
)

internal val LightColors = lightColorScheme(
    primary = Color(0xFF0E6C3F), onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFF9FF5BB), onPrimaryContainer = Color(0xFF00522D),
    inversePrimary = Color(0xFF84D8A1), secondary = Color(0xFF4B6454),
    onSecondary = Color(0xFFFFFFFF), secondaryContainer = Color(0xFFCEE9D4),
    onSecondaryContainer = Color(0xFF344C3D), tertiary = Color(0xFF835333),
    onTertiary = Color(0xFFFFFFFF), tertiaryContainer = Color(0xFFFFDBC7),
    onTertiaryContainer = Color(0xFF673C1E), error = Color(0xFFBA1A1A),
    onError = Color(0xFFFFFFFF), errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF93000A), background = Color(0xFFF8FAF5),
    onBackground = Color(0xFF191C1A), surface = Color(0xFFF8FAF5),
    onSurface = Color(0xFF191C1A), surfaceVariant = Color(0xFFDCE5DB),
    onSurfaceVariant = Color(0xFF414942), surfaceTint = Color(0xFF0E6C3F),
    inverseSurface = Color(0xFF2E312E), inverseOnSurface = Color(0xFFF0F1EC),
    outline = Color(0xFF717971), outlineVariant = Color(0xFFC0C9C0),
    scrim = Color(0xFF000000), surfaceBright = Color(0xFFF8FAF5),
    surfaceDim = Color(0xFFD9DBD6), surfaceContainer = Color(0xFFEDEEE9),
    surfaceContainerHigh = Color(0xFFE7E9E4), surfaceContainerHighest = Color(0xFFE1E3DE),
    surfaceContainerLow = Color(0xFFF3F4EF), surfaceContainerLowest = Color(0xFFFFFFFF),
)

/** Pure-black surfaces; containers step through neutral T4–T12 so cards stay distinguishable. */
internal fun ColorScheme.toAmoled(): ColorScheme = copy(
    background = Color.Black,
    surface = Color.Black,
    surfaceDim = Color.Black,
    surfaceContainerLowest = Color.Black,
    surfaceContainerLow = Color(0xFF0C0F0C),
    surfaceContainer = Color(0xFF111411),
    surfaceContainerHigh = Color(0xFF191C1A),
    surfaceContainerHighest = Color(0xFF1D201D),
    surfaceBright = Color(0xFF282B28),
)

/** Raw brand hexes for use outside composition (notification accent, splash). Never semantic: statuses use M3 roles. */
object BrandPalette {
    /** 5.86:1 on the dark surface. */
    val Ruby = Color(0xFFE8667B)
    /** 6.13:1 on the light surface. */
    val RubyOnLight = Color(0xFFA7354B)
    /** "Connected" glow, 10.95:1 on the dark surface. */
    val SuiDream = Color(0xFF57DF92)
    val SuiDreamPale = Color(0xFFC2FFD2)
    /** Launcher and splash background. */
    val DressDeep = Color(0xFF003920)
    val Ivory = Color(0xFFF3EFE4)
}
