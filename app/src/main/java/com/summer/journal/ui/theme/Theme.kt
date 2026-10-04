package com.summer.journal.ui.theme

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.BackgroundTheme

/* ══════════════════════════════════════════════════════════════════════
   设计 token —— 与视觉稿 1:1，改色只改这里
   ══════════════════════════════════════════════════════════════════════ */

object SummerPalette {
    // 晚霞主色序（取自参考插画）
    val SkyDeep = Color(0xFF1B2450)      // 深空蓝
    val SkyMid = Color(0xFF2C3468)
    val Dusk = Color(0xFF5B5AA0)         // 暮紫
    val Lilac = Color(0xFF8E82D6)        // 淡紫
    val SunsetPink = Color(0xFFE8A3AE)   // 霞粉
    val SunsetPinkLight = Color(0xFFF6CFC9)
    val Amber = Color(0xFFF09A5C)        // 暖橙
    val AmberLight = Color(0xFFFFC98F)
    val Teal = Color(0xFF5ECBC2)         // 青
    val TealLight = Color(0xFFA9E8E1)

    // 中性
    val Background = Color(0xFFF8F4F2)   // 柔和暖白
    val Card = Color(0xFFFFFFFF)
    val Hairline = Color(0xFFEEE7E2)
    val HairlineSoft = Color(0xFFF4EFEB)
    val Ink = Color(0xFF2A2838)
    val InkSecondary = Color(0xFF5D5A72)
    val InkTertiary = Color(0xFF9895AA)

    // 语义色（国内习惯：涨红跌绿；这里只用于日程分类与状态）
    val CategoryTeal = Color(0xFFE7F7F5)
    val CategoryViolet = Color(0xFFEFEDFB)
    val CategoryPink = Color(0xFFFDE7E4)
    val CategoryAmber = Color(0xFFFFF0DC)
}

/** 背景底色的 8 套预设。用 key 存 DataStore，切换零成本（不是 8 张图） */
enum class BackgroundColorKey(
    val label: String,
    val light: Color,
    val dark: Color,
) {
    // 8 套底色必须「一眼能分辨」。上一版都贴着白，只差约 2% 色相，
    // 用户反馈「选了跟没选一样」。这里把色相与明度都拉开：
    //   青 / 紫 / 粉 / 橙 / 绿 各占一个明显色相位，浅色底也保持低饱和（不刺眼）。
    WARM_WHITE("暖白", Color(0xFFF8F2EC), Color(0xFF17161D)),
    MIST_TEAL("晨雾青", Color(0xFFD9EFEA), Color(0xFF0F1B1E)),
    DUSK_PURPLE("薄暮紫", Color(0xFFE8E1F8), Color(0xFF191330)),
    SUNSET_PINK("霞粉", Color(0xFFFADDE0), Color(0xFF2A1219)),
    PEACH("蜜桃橙", Color(0xFFFBE4CC), Color(0xFF231609)),
    NIGHT_BLUE("夜幕蓝", Color(0xFF1F2547), Color(0xFF1F2547)),
    MOSS("苔绿", Color(0xFFE0EEDA), Color(0xFF141F10)),
    CUSTOM("自定义", Color(0xFFF8F2EC), Color(0xFF17161D)),
    ;

    /** 按当前明暗模式取实际底色。设置页的色卡也要用它，否则色卡和实际效果对不上 */
    fun resolve(dark: Boolean): Color = if (dark) this.dark else this.light

    /** 这套底色下，直接画在背景上的文字该用深色还是浅色 */
    fun foregroundOn(dark: Boolean): Color =
        if (shouldInvertText(resolve(dark))) Color(0xFFEDEBF5) else SummerPalette.Ink

    companion object {
        /** 从 DataStore 里存的字符串解析；非法值一律回落暖白 */
        fun fromKey(key: String?): BackgroundColorKey =
            entries.firstOrNull { it.name.equals(key, ignoreCase = true) } ?: WARM_WHITE
    }
}

/**
 * 品牌扩展 token。
 * Material3 的 ColorScheme 表达不了「卡片圆角 20 / 大卡 26」这类东西，
 * 用 CompositionLocal 单独带一份，避免到处硬编码 dp。
 */
data class SummerTokens(
    val radiusCard: Dp = 20.dp,
    val radiusLarge: Dp = 26.dp,
    val radiusInput: Dp = 16.dp,
    val radiusPill: Dp = 999.dp,
    val screenPadding: Dp = 20.dp,
    val gapSection: Dp = 22.dp,
    val gapItem: Dp = 12.dp,
    val tabBarHeight: Dp = 62.dp,
    val statusBarHeight: Dp = 62.dp,
    val heroBrush: Brush = Brush.linearGradient(
        listOf(SummerPalette.SkyDeep, SummerPalette.Dusk, SummerPalette.AmberLight),
    ),
)

val LocalSummerTokens = staticCompositionLocalOf { SummerTokens() }

/** 当前背景主题，背景引擎读它决定画纯色还是画图 */
val LocalBackgroundTheme = staticCompositionLocalOf { BackgroundTheme() }

/* ══════════════════════════════════════════════════════════════════════
   字体
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 荣耀 / 华为机型自带 HarmonyOS Sans，直接复用系统字体，包体不加体积。
 * 其余机型由系统逐级回退（Noto Sans SC / 思源黑体 / 系统默认无衬线）。
 *
 * 想让所有机型视觉一致时，把 MiSans-Regular.ttf 放进 res/font 并在这里注册：
 *   FontFamily(Font(R.font.misans_regular, FontWeight.Normal), ...)
 * 代价是包体 +4MB 左右 —— 是否值得，取决于你们对一致性的要求。
 */
val SummerFontFamily = FontFamily.Default

val SummerTypography = Typography(
    // 页面大标题：视觉稿 24–32 / 700，且**每个页面统一**
    headlineLarge = TextStyle(
        fontFamily = SummerFontFamily,
        fontSize = 24.sp, fontWeight = FontWeight.Bold, lineHeight = 32.sp,
    ),
    // 头图上的月份
    displaySmall = TextStyle(
        fontFamily = SummerFontFamily,
        fontSize = 32.sp, fontWeight = FontWeight.Bold, letterSpacing = (-0.6).sp, lineHeight = 36.sp,
    ),
    // 区块标题 15.5 / 650
    titleMedium = TextStyle(
        fontFamily = SummerFontFamily,
        fontSize = 15.5.sp, fontWeight = FontWeight.W600, lineHeight = 20.sp,
    ),
    bodyLarge = TextStyle(
        fontFamily = SummerFontFamily,
        fontSize = 14.sp, fontWeight = FontWeight.Normal, lineHeight = 22.sp,
    ),
    labelSmall = TextStyle(
        fontFamily = SummerFontFamily,
        fontSize = 11.sp, fontWeight = FontWeight.Normal, lineHeight = 16.sp,
    ),
)

/* ══════════════════════════════════════════════════════════════════════
   ColorScheme
   ══════════════════════════════════════════════════════════════════════ */

private val LightScheme = lightColorScheme(
    primary = SummerPalette.Dusk,
    onPrimary = Color.White,
    primaryContainer = SummerPalette.Lilac,
    secondary = SummerPalette.Teal,
    tertiary = SummerPalette.Amber,
    background = SummerPalette.Background,
    onBackground = SummerPalette.Ink,
    surface = SummerPalette.Card,
    onSurface = SummerPalette.Ink,
    surfaceVariant = SummerPalette.HairlineSoft,
    onSurfaceVariant = SummerPalette.InkSecondary,
    outline = SummerPalette.Hairline,
)

private val DarkScheme = darkColorScheme(
    primary = SummerPalette.Lilac,
    onPrimary = SummerPalette.SkyDeep,
    secondary = SummerPalette.Teal,
    tertiary = SummerPalette.AmberLight,
    background = Color(0xFF17161D),
    onBackground = Color(0xFFEDEBF5),
    surface = Color(0xFF20202A),
    onSurface = Color(0xFFEDEBF5),
    surfaceVariant = Color(0xFF2A2A36),
    onSurfaceVariant = Color(0xFFA8A5BC),
    outline = Color(0xFF3A3A46),
)

@Composable
fun SummerJournalTheme(
    darkTheme: Boolean = isSystemInDarkTheme(),
    backgroundTheme: BackgroundTheme = BackgroundTheme(),
    content: @Composable () -> Unit,
) {
    MaterialTheme(
        colorScheme = if (darkTheme) DarkScheme else LightScheme,
        typography = SummerTypography,
    ) {
        CompositionLocalProvider(
            LocalSummerTokens provides SummerTokens(),
            LocalBackgroundTheme provides backgroundTheme,
            content = content,
        )
    }
}

/* ══════════════════════════════════════════════════════════════════════
   背景引擎
   ══════════════════════════════════════════════════════════════════════ */

/**
 * 背景色的 Brush。
 *
 * 做成了一个轻量的双色渐变（而不是纯色）——
 * 纯色底在整屏看的时候会显得「死板」，加约 6% 的色相偏移就有柔和的过渡感，
 * 同时远看仍然是「一个颜色」，不会喧宾夺主。
 *
 * ★ 为什么从 2% 提到 6%：
 *   上一版偏移只有约 1.2%，在手机屏上几乎看不出渐变，
 *   配合本身就很接近的底色，整体观感就是「一片死白」。
 *   提到 6% 后，渐变本身也有了一层可见的层次，底色差异才被「看得见」。
 */
fun brushFor(key: String, dark: Boolean): Brush {
    val base = BackgroundColorKey.fromKey(key).resolve(dark)
    val end = if (base.luminance() < 0.2f) {
        // 深色底：往稍微偏蓝紫的方向走，更像夜空
        base.copy(red = (base.red + 0.035f).coerceAtMost(1f), blue = (base.blue + 0.06f).coerceAtMost(1f))
    } else {
        // 浅色底：往暖一点的方向走（红 +6% / 绿 +1.5% / 蓝 -2%，整体偏暖且可辨）
        base.copy(
            red = (base.red + 0.06f).coerceAtMost(1f),
            green = (base.green + 0.015f).coerceAtMost(1f),
            blue = (base.blue - 0.02f).coerceAtLeast(0f),
        )
    }
    return Brush.linearGradient(listOf(base, end))
}

/**
 * 文字是否该反色。
 *
 * ★ 用感知亮度（luminance）而不是「RGB 谁大」——
 *   后者在处理晚霞那种「紫中带橙」的背景时会判错，
 *   结果就是白字压在浅橙上，完全看不清。
 *   0.45 这个阈值是在视觉稿的那 8 套底色上试出来的分界。
 */
fun shouldInvertText(backgroundColor: Color): Boolean = backgroundColor.luminance() < 0.45f

/**
 * 页面级文字颜色。
 *
 * 注意作用范围：**只针对直接画在背景上的文字**（页面大标题、区块标题这些），
 * 白卡片里的文字一律不动 —— 卡片自己保证对比度，跟着背景变色反而会出问题。
 * 由 AppBackground 根据「当前生效的背景 + 用户是否开启自动反色」计算后注入。
 */
val LocalPageForeground = staticCompositionLocalOf { SummerPalette.Ink }

/** 页面级次级文字（说明文字、计数） */
val LocalPageForegroundSecondary = staticCompositionLocalOf { SummerPalette.InkTertiary }
