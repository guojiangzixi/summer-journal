package com.summer.journal.ui.theme

import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import com.summer.journal.domain.model.BackgroundScope
import com.summer.journal.domain.model.BackgroundTheme
import java.time.LocalTime

/**
 * 背景引擎。
 *
 * 优先级：自定义图片 > 预设底色。
 * 作用范围由 [BackgroundTheme.scope] 决定；文字颜色由 [BackgroundTheme.autoInvertText] + 背景明暗决定。
 *
 * 实现上的四个决定：
 *
 * 1. **模糊是实时算的，不是预生成素材**。用户拖滑块要立刻看到效果，
 *    预生成多份模糊图会让「实时」这个体验消失。
 *    `Modifier.blur` 只在 API 31+ 生效，低版本自动忽略（不崩、不退化）。
 *
 * 2. **`blurDp <= 0` 时不挂 blur 修饰符**。不要指望 blur(0.dp) 一定是无操作，
 *    显式跳过更稳，也省掉一次 RenderEffect 的创建。
 *
 * 3. **遮罩是纯黑叠加，不改图片本身**。这样「遮罩浓度」是独立可控的一维，
 *    用户拉到 0 能看到原图，拉到 80 也不会把图片糊掉。
 *
 * 4. **文字反色只作用于「直接画在背景上的文字」**（页面标题、区块标题），
 *    白卡片里的文字一律不动 —— 卡片自己保证对比度，跟着背景变反而会出错。
 *    通过 [LocalPageForeground] / [LocalPageForegroundSecondary] 注入。
 */
@Composable
fun AppBackground(
    theme: BackgroundTheme,
    /** 当前是不是日历页。用于 BackgroundScope.CALENDAR_ONLY */
    isCalendarPage: Boolean,
    content: @Composable () -> Unit,
) {
    val systemDark = isSystemInDarkTheme()
    val image = rememberBackgroundImage(theme.imageRelativePath)

    // ── ① 这一页到底用不用自定义背景 ──
    val active = when (theme.scope) {
        BackgroundScope.ALL_PAGES -> true
        BackgroundScope.CALENDAR_ONLY -> isCalendarPage
        // 跟随时间只影响明暗方案，不影响是否启用
        BackgroundScope.AUTO_BY_TIME -> true
    }

    // ── ② 明暗方案 ──
    val dark = when (theme.scope) {
        // 「白天浅色、日落后深色」。6:00–17:59 算白天。
        BackgroundScope.AUTO_BY_TIME -> LocalTime.now().hour !in 6..17
        else -> systemDark
    }

    // ── ③ 直接画在背景上的文字用什么颜色 ──
    val onBackground = remember(active, theme.autoInvertText, image, theme.colorKey, dark, theme.scrimAlpha) {
        when {
            !active -> SummerPalette.Ink
            !theme.autoInvertText -> SummerPalette.Ink
            // 照片背景：默认遮罩会把画面压暗，统一配浅色文字。
            // 遮罩调很小时设置页会提示，不在这里偷偷改用户的数值。
            image != null -> Color(0xFFEDEBF5)
            else -> {
                val base = BackgroundColorKey.fromKey(theme.colorKey).resolve(dark)
                if (shouldInvertText(base)) Color(0xFFEDEBF5) else SummerPalette.Ink
            }
        }
    }
    val onBackgroundSecondary = onBackground.copy(alpha = 0.68f)

    Box(modifier = Modifier.fillMaxSize()) {
        if (active) {
            BackgroundLayer(theme = theme, dark = dark, image = image)
        } else {
            // 不受自定义背景影响的页面：用主题自带的中性底
            Box(modifier = Modifier.fillMaxSize().background(SummerPalette.Background))
        }

        CompositionLocalProvider(
            LocalPageForeground provides onBackground,
            LocalPageForegroundSecondary provides onBackgroundSecondary,
            content = content,
        )
    }
}

@Composable
private fun BackgroundLayer(
    theme: BackgroundTheme,
    dark: Boolean,
    image: androidx.compose.ui.graphics.ImageBitmap?,
) {
    if (image != null) {
        Image(
            bitmap = image,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .then(
                    // 显式判断，不依赖 blur(0.dp) 的无操作行为
                    if (theme.blurDp > 0) Modifier.blur(theme.blurDp.dp) else Modifier
                ),
        )
        if (theme.scrimAlpha > 0f) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = theme.scrimAlpha.coerceIn(0f, 0.8f))),
            )
        }
    } else {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(brushFor(theme.colorKey, dark)),
        )
    }
}

/**
 * 设置页的实时预览用。
 * 和真实背景走同一套逻辑（同一个 brushFor / 同一个遮罩公式），
 * 避免「预览好看、进去发现不一样」这种经典问题。
 */
@Composable
fun BackgroundPreviewBox(
    theme: BackgroundTheme,
    modifier: Modifier = Modifier,
    content: @Composable () -> Unit,
) {
    val dark = isSystemInDarkTheme()
    val image = rememberBackgroundImage(theme.imageRelativePath)

    Box(modifier = modifier) {
        if (image != null) {
            Image(
                bitmap = image,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .then(if (theme.blurDp > 0) Modifier.blur(theme.blurDp.dp) else Modifier),
            )
            if (theme.scrimAlpha > 0f) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = theme.scrimAlpha.coerceIn(0f, 0.8f))),
                )
            }
        } else {
            Box(modifier = Modifier.fillMaxSize().background(brushFor(theme.colorKey, dark)))
        }
        content()
    }
}

/** 供预览卡片判断自己的文字该用深色还是浅色 */
fun previewForeground(theme: BackgroundTheme, systemDark: Boolean, hasImage: Boolean): Color = when {
    hasImage -> Color(0xFFEDEBF5)
    shouldInvertText(BackgroundColorKey.fromKey(theme.colorKey).resolve(systemDark)) -> Color(0xFFEDEBF5)
    else -> SummerPalette.Ink
}

/** 当前生效背景的感知亮度，仅用于调试与测试 */
fun effectiveBackgroundLuminance(theme: BackgroundTheme, systemDark: Boolean): Float =
    BackgroundColorKey.fromKey(theme.colorKey).resolve(systemDark).luminance()
