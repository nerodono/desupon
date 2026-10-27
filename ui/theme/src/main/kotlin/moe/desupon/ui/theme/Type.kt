package moe.desupon.ui.theme

import androidx.compose.material3.Typography
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.sp

private val Base = Typography()

internal val DesuponTypography = Base.copy(
    titleMedium = Base.titleMedium.copy(fontWeight = FontWeight.SemiBold),
    // Card captions ("ТРАФИК", "ПОДПИСКА") are set uppercase by callers; wider tracking keeps them legible.
    labelSmall = Base.labelSmall.copy(fontWeight = FontWeight.SemiBold, letterSpacing = 1.2.sp),
)

/** Session timer: tabular figures so digits don't jitter as they tick. */
val Typography.timer: TextStyle
    get() = headlineSmall.copy(
        fontWeight = FontWeight.SemiBold,
        fontFeatureSettings = "tnum",
        letterSpacing = 0.5.sp,
    )

/** Tabular figures for any changing numeric readout (traffic, counters). */
val TextStyle.tabular: TextStyle
    get() = copy(fontFeatureSettings = "tnum")
