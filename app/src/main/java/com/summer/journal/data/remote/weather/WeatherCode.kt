package com.summer.journal.data.remote.weather

import com.summer.journal.domain.model.SkyCondition

/**
 * WMO Weather interpretation code → 中文描述 + 视觉条件
 * 来源：https://open-meteo.com/en/docs（WMO Weather interpretation codes）
 *
 * 注意：Open-Meteo 不返回天气文案，只返回 code。
 * 所有中文描述都在这里映射，别散落到 UI 层。
 */
object WeatherCode {

    fun toCondition(code: Int): SkyCondition = when (code) {
        0, 1 -> SkyCondition.CLEAR
        2 -> SkyCondition.PARTLY_CLOUDY
        3 -> SkyCondition.CLOUDY
        45, 48 -> SkyCondition.FOG
        51, 53, 55, 56, 57 -> SkyCondition.RAIN
        61, 63, 65, 66, 67 -> SkyCondition.RAIN
        71, 73, 75, 77 -> SkyCondition.SNOW
        80, 81, 82 -> SkyCondition.SHOWER
        85, 86 -> SkyCondition.SNOW
        95, 96, 99 -> SkyCondition.THUNDER
        else -> SkyCondition.CLOUDY
    }

    fun toDescription(code: Int): String = when (code) {
        0 -> "晴"
        1 -> "晴间多云"
        2 -> "晴转多云"
        3 -> "阴"
        45 -> "有雾"
        48 -> "冻雾"
        51 -> "小毛毛雨"
        53 -> "毛毛雨"
        55 -> "浓毛毛雨"
        56 -> "冻毛毛雨"
        57 -> "浓冻毛毛雨"
        61 -> "小雨"
        63 -> "中雨"
        65 -> "大雨"
        66 -> "冻雨"
        67 -> "强冻雨"
        71 -> "小雪"
        73 -> "中雪"
        75 -> "大雪"
        77 -> "米雪"
        80 -> "阵雨"
        81 -> "强阵雨"
        82 -> "暴雨"
        85 -> "小阵雪"
        86 -> "大阵雪"
        95 -> "雷阵雨"
        96 -> "雷阵雨伴小冰雹"
        99 -> "雷阵雨伴大冰雹"
        else -> "未知"
    }

    /**
     * 映射到 app 内已有的 SVG symbol 名称（见视觉稿的 i-sun / i-partly / i-cloud / i-rain 等）。
     * 白天/夜间用同一套图标，避免再维护一套夜间图标。
     */
    fun toIconKey(code: Int): String = when (toCondition(code)) {
        SkyCondition.CLEAR -> "sun"
        SkyCondition.PARTLY_CLOUDY -> "partly"
        SkyCondition.CLOUDY, SkyCondition.FOG -> "cloud"
        SkyCondition.RAIN, SkyCondition.SHOWER -> "rain"
        SkyCondition.SNOW -> "snow"
        SkyCondition.THUNDER -> "storm"
    }

    /** 用一句话描述「今天要不要带伞」，给日历页的 chip 用 */
    fun umbrellaHint(precipProbability: Int, code: Int): String? = when {
        precipProbability >= 60 || toCondition(code) in listOf(SkyCondition.RAIN, SkyCondition.SHOWER, SkyCondition.THUNDER) ->
            "记得带伞"
        precipProbability >= 30 -> "可能有雨"
        else -> null
    }
}
