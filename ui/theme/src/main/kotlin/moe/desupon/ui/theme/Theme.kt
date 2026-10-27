package moe.desupon.ui.theme

import android.os.Build
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.dynamicDarkColorScheme
import androidx.compose.material3.dynamicLightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext

enum class ThemeMode { Dark, Light, System }

/** Brand accents outside the M3 roles; decorative only, never a status signal on their own. */
@Immutable
data class BrandColors(
    val ruby: Color,
    val suiDream: Color,
    val suiDreamPale: Color,
    val iconBackground: Color,
    val ivory: Color,
)

private val DarkBrand = BrandColors(
    ruby = BrandPalette.Ruby,
    suiDream = BrandPalette.SuiDream,
    suiDreamPale = BrandPalette.SuiDreamPale,
    iconBackground = BrandPalette.DressDeep,
    ivory = BrandPalette.Ivory,
)

private val LightBrand = DarkBrand.copy(ruby = BrandPalette.RubyOnLight)

val LocalBrandColors = staticCompositionLocalOf { DarkBrand }

/**
 * @param amoled pure-black surfaces; applies only when the resolved theme is dark.
 * @param dynamicColor wallpaper colors (Android 12+); off by default to keep the brand palette.
 */
@Composable
fun DesuponTheme(
    themeMode: ThemeMode = ThemeMode.Dark,
    amoled: Boolean = false,
    desuMode: Boolean = false,
    dynamicColor: Boolean = false,
    content: @Composable () -> Unit,
) {
    val dark = when (themeMode) {
        ThemeMode.Dark -> true
        ThemeMode.Light -> false
        ThemeMode.System -> isSystemInDarkTheme()
    }
    val base = when {
        dynamicColor && Build.VERSION.SDK_INT >= Build.VERSION_CODES.S -> {
            val context = LocalContext.current
            if (dark) dynamicDarkColorScheme(context) else dynamicLightColorScheme(context)
        }
        dark -> DarkColors
        else -> LightColors
    }
    val scheme = if (dark && amoled) base.toAmoled() else base

    CompositionLocalProvider(
        LocalBrandColors provides if (dark) DarkBrand else LightBrand,
        LocalDesuMode provides desuMode,
    ) {
        MaterialTheme(
            colorScheme = scheme,
            typography = DesuponTypography,
            shapes = DesuponShapes,
            content = content,
        )
    }
}

object DesuponTheme {
    val brand: BrandColors
        @Composable @ReadOnlyComposable get() = LocalBrandColors.current

    val desuMode: Boolean
        @Composable @ReadOnlyComposable get() = LocalDesuMode.current
}
