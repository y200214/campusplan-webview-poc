package jp.naramed.campusplanpoc.ui

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Shapes
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * アプリの見た目。
 *
 * 方針:
 *  - 落ち着いたインディゴ〜ブルーを主色にしたモダンな Material3 配色。
 *  - 配色は固定。Dynamic Color（壁紙由来）は端末ごとに主色がくすむため使わない。
 *  - ライト / ダーク 両対応。端末設定に追従する。
 *  - フォントは端末標準の sans-serif（日本語は Noto Sans CJK JP）。
 */

// --- ブランド色 ---
private val BrandPrimary = Color(0xFF3D5AFE)      // インディゴA200 寄りの青
private val BrandPrimaryDark = Color(0xFFB3C2FF)

private val LightColors = lightColorScheme(
    primary = BrandPrimary,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDDE1FF),
    onPrimaryContainer = Color(0xFF00105C),
    secondary = Color(0xFF5A5D72),
    onSecondary = Color.White,
    secondaryContainer = Color(0xFFDFE1F9),
    onSecondaryContainer = Color(0xFF171B2C),
    tertiary = Color(0xFF00658E),
    onTertiary = Color.White,
    background = Color(0xFFFBFBFF),
    onBackground = Color(0xFF1B1B1F),
    surface = Color(0xFFFBFBFF),
    onSurface = Color(0xFF1B1B1F),
    surfaceVariant = Color(0xFFE3E1EC),
    onSurfaceVariant = Color(0xFF46464F),
    outline = Color(0xFF767680),
    outlineVariant = Color(0xFFC7C5D0),
    error = Color(0xFFBA1A1A),
    onError = Color.White,
    errorContainer = Color(0xFFFFDAD6),
    onErrorContainer = Color(0xFF410002),
)

private val DarkColors = darkColorScheme(
    primary = BrandPrimaryDark,
    onPrimary = Color(0xFF001A8F),
    // 面として広く使うので彩度を落とす。鮮やかな青のままだとカードがうるさい
    primaryContainer = Color(0xFF262E5C),
    onPrimaryContainer = Color(0xFFDDE1FF),
    secondary = Color(0xFFC3C5DD),
    onSecondary = Color(0xFF2C2F42),
    secondaryContainer = Color(0xFF424659),
    onSecondaryContainer = Color(0xFFDFE1F9),
    tertiary = Color(0xFF82CFFF),
    onTertiary = Color(0xFF00344C),
    background = Color(0xFF1B1B1F),
    onBackground = Color(0xFFE4E1E6),
    surface = Color(0xFF131316),
    onSurface = Color(0xFFE4E1E6),
    surfaceVariant = Color(0xFF46464F),
    onSurfaceVariant = Color(0xFFC7C5D0),
    outline = Color(0xFF90909A),
    outlineVariant = Color(0xFF46464F),
    error = Color(0xFFFFB4AB),
    onError = Color(0xFF690005),
    errorContainer = Color(0xFF93000A),
    onErrorContainer = Color(0xFFFFDAD6),
)

/** 成功・警告など Material3 の役割に無い色 */
object AppColors {
    val successLight = Color(0xFF1E7B47)
    val successDark = Color(0xFF7ED9A0)
}

private val AppTypography = Typography(
    headlineMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700, fontSize = 26.sp, lineHeight = 34.sp,
    ),
    headlineSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700, fontSize = 22.sp, lineHeight = 30.sp,
    ),
    titleLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W700, fontSize = 19.sp, lineHeight = 26.sp,
    ),
    titleMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W600, fontSize = 16.sp, lineHeight = 24.sp,
    ),
    titleSmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 22.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W400, fontSize = 16.sp, lineHeight = 26.sp,
    ),
    bodyMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W400, fontSize = 14.sp, lineHeight = 22.sp,
    ),
    bodySmall = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W400, fontSize = 13.sp, lineHeight = 19.sp,
    ),
    labelLarge = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W600, fontSize = 15.sp, lineHeight = 20.sp,
    ),
    labelMedium = TextStyle(
        fontFamily = FontFamily.SansSerif,
        fontWeight = FontWeight.W600, fontSize = 12.sp, lineHeight = 16.sp,
    ),
)

/** 全体的にやや丸めのモダンな角丸 */
private val AppShapes = Shapes(
    extraSmall = RoundedCornerShape(8.dp),
    small = RoundedCornerShape(12.dp),
    medium = RoundedCornerShape(16.dp),
    large = RoundedCornerShape(20.dp),
    extraLarge = RoundedCornerShape(28.dp),
)

@Composable
fun CampusPlanPocTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    content: @Composable () -> Unit,
) {
    /*
     * Dynamic Color は使わない。
     * 壁紙由来の色になるため、端末によっては主色がくすんで意図した見た目にならない
     * （実機で確認）。配色を自分で決めたほうが結果が安定する。
     */
    val colorScheme = if (darkTheme) DarkColors else LightColors

    MaterialTheme(
        colorScheme = colorScheme,
        typography = AppTypography,
        shapes = AppShapes,
        content = content,
    )
}
