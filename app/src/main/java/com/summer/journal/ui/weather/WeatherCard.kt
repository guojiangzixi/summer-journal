package com.summer.journal.ui.weather

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.summer.journal.domain.model.SkyCondition
import com.summer.journal.domain.model.WeatherSnapshot
import com.summer.journal.ui.AppViewModel
import com.summer.journal.ui.theme.SummerPalette
import java.time.LocalDateTime

/**
 * 日历页上的天气卡。
 *
 * 三种状态都必须好看，因为它们是真实会出现的：
 *  · Ready          → 正常显示
 *  · NeedManualPick → 定位拿不到，引导用户手选城市（**不是错误**）
 *  · Failed         → 有定位但接口挂了，显示重试
 *  · 没数据         → 整块隐藏，不留空壳
 */
@Composable
fun WeatherCard(
    snapshot: WeatherSnapshot?,
    status: AppViewModel.WeatherStatus,
    cityName: String?,
    onPickCity: () -> Unit,
    onRetry: () -> Unit,
    onEnableLocation: () -> Unit,
    modifier: Modifier = Modifier,
) {
    when {
        snapshot != null -> ReadyCard(snapshot = snapshot, modifier = modifier)
        status is AppViewModel.WeatherStatus.NeedManualPick -> PromptCard(
            title = "打开定位，或手动选择城市",
            action = "开启定位",
            onAction = onEnableLocation,
            // 没有定位权限 / 定位失败时，手动选城市是永远可用的兜底
            secondaryAction = "手动选城市",
            onSecondaryAction = onPickCity,
            modifier = modifier,
        )
        status is AppViewModel.WeatherStatus.Failed -> PromptCard(
            title = "暂时拿不到天气",
            action = "重试",
            onAction = onRetry,
            modifier = modifier,
        )
        else -> Unit   // Loading：不留空壳，避免布局跳动
    }
}

@Composable
private fun ReadyCard(snapshot: WeatherSnapshot, modifier: Modifier = Modifier) {
    val now = LocalDateTime.now()
    val hours = snapshot.hoursFrom(now, count = 6)

    Column(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(
                Brush.linearGradient(listOf(Color.White, Color(0xFFFFFDFB), Color(0xFFFFF6EE)))
            )
            .padding(horizontal = 16.dp, vertical = 15.dp),
        verticalArrangement = Arrangement.spacedBy(13.dp),
    ) {
        // 第一行：图标 + 温度 + 描述 + 地点
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(11.dp)) {
            Box(
                modifier = Modifier
                    .width(40.dp)
                    .height(40.dp)
                    .clip(RoundedCornerShape(14.dp))
                    .background(Brush.linearGradient(listOf(Color(0xFFFFF4E6), Color(0xFFFFE5C4)))),
                contentAlignment = Alignment.Center,
            ) {
                Text(skyEmoji(snapshot.current.condition), fontSize = 18.sp)
            }

            Text(
                text = "${snapshot.current.temperature.toInt()}°",
                fontSize = 27.sp,
                fontWeight = FontWeight.Bold,
                color = SummerPalette.Ink,
            )

            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = snapshot.current.description,
                    fontSize = 13.5.sp,
                    fontWeight = FontWeight.SemiBold,
                    color = SummerPalette.Ink,
                )
                Text(
                    text = "体感 ${snapshot.current.apparentTemperature.toInt()}° · 湿度 ${snapshot.current.humidity}%",
                    fontSize = 11.sp,
                    color = SummerPalette.InkTertiary,
                )
            }

            Text(
                text = snapshot.cityName,
                fontSize = 10.5.sp,
                color = SummerPalette.InkTertiary,
            )
        }

        // 第二行：整点预报
        if (hours.isNotEmpty()) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                hours.forEach { hour ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            text = "%d时".format(hour.time.hour),
                            fontSize = 9.5.sp,
                            color = SummerPalette.InkTertiary,
                        )
                        Text(text = skyEmoji(hour.condition), fontSize = 13.sp)
                        Text(
                            text = "${hour.temperature.toInt()}°",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = SummerPalette.InkSecondary,
                        )
                    }
                }
            }
        }

        // 第三行：降水 / 空气 / 未来 7 天入口
        val today = snapshot.daily.firstOrNull()
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(
                text = "降水 ${today?.precipitationProbability ?: 0}%",
                fontSize = 10.5.sp,
                color = SummerPalette.InkTertiary,
            )
            snapshot.air?.let {
                Text(text = "空气 ${it.level} ${it.europeanAqi}", fontSize = 10.5.sp, color = SummerPalette.InkTertiary)
            }
            Text(
                text = "数据 ${snapshot.fetchedAt.hour}:%02d".format(snapshot.fetchedAt.minute),
                fontSize = 10.5.sp,
                color = SummerPalette.InkTertiary,
            )
        }
    }
}

@Composable
private fun PromptCard(
    title: String,
    action: String,
    onAction: () -> Unit,
    secondaryAction: String? = null,
    onSecondaryAction: (() -> Unit)? = null,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(SummerPalette.Card)
            .padding(horizontal = 16.dp, vertical = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(
            text = title,
            fontSize = 13.sp,
            color = SummerPalette.InkSecondary,
            modifier = Modifier.weight(1f),
        )
        Row(
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            // 次要动作用描边样式，视觉上退到主操作之后
            if (secondaryAction != null && onSecondaryAction != null) {
                Text(
                    text = secondaryAction,
                    fontSize = 12.sp,
                    color = SummerPalette.InkTertiary,
                    modifier = Modifier
                        .clip(RoundedCornerShape(999.dp))
                        .clickable { onSecondaryAction() }
                        .padding(horizontal = 11.dp, vertical = 6.dp),
                )
            }
            Text(
                text = action,
                fontSize = 12.5.sp,
                fontWeight = FontWeight.SemiBold,
                color = SummerPalette.Dusk,
                modifier = Modifier
                    .clip(RoundedCornerShape(999.dp))
                    .clickable { onAction() }
                    .padding(horizontal = 12.dp, vertical = 6.dp),
            )
        }
    }
}

/**
 * 天气图标。
 * ⚠️ 这里用 Unicode 天气符号是权宜之计 ——
 *    正式实现请换成 vector drawable（或复用视觉稿里那套 SVG），
 *    不同 ROM 的 emoji 字体渲染差异很大，荣耀和原生 Android 长得完全不一样。
 */
internal fun skyEmoji(condition: SkyCondition): String = when (condition) {
    SkyCondition.CLEAR -> "☀"
    SkyCondition.PARTLY_CLOUDY -> "⛅"
    SkyCondition.CLOUDY -> "☁"
    SkyCondition.RAIN, SkyCondition.SHOWER -> "☔"
    SkyCondition.SNOW -> "❄"
    SkyCondition.THUNDER -> "⚡"
    SkyCondition.FOG -> "🌫"
}
