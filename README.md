# 夏日手札 · Summer Journal

> 一个只给自己用的 Android 日记本：**日历、课表、手札**三合一。
> 离线优先、无广告、不要账号、不采集数据。

[![Android CI](https://github.com/guojiangzixi/summer-journal/actions/workflows/android.yml/badge.svg)](https://github.com/guojiangzixi/summer-journal/actions/workflows/android.yml)
[![Release](https://img.shields.io/github/v/release/guojiangzixi/summer-journal?label=release)](https://github.com/guojiangzixi/summer-journal/releases)
[![minSdk](https://img.shields.io/badge/minSdk-26%20(Android%208.0)-3DDC84)](https://developer.android.com/about/versions/oreo)
[![License](https://img.shields.io/badge/license-MIT-blue)](LICENSE)

---

## 这是什么

一款单 Activity 的个人日程 App，用 Kotlin + Jetpack Compose 写成。三块内容：

| 模块 | 一句话 |
|---|---|
| **日历** | 月视图看日程，带农历与节假日 |
| **课表** | 手动添加或**拍照 OCR 识别**导入，可按周查看 |
| **手札** | 图文音附件齐备的笔记，可搜索、可分类，图片能提取文字 |

设计目标很具体：**在没有 Google 服务的国产手机上，也要能稳定跑。**
所以定位走系统 API 而不是 GMS，OCR 用离线模型，天气用免 Key 的公开接口。

---

## 功能

### 日历
- 月视图 + 当日日程列表，新建 / 删除日程
- **农历**（离线算法，覆盖 1900–2100）：月视图每格显示农历日，选中日标题显示「农历X月X日」
- 法定节假日角标，农历与节假日都可在设置里开关
- 新建日程默认**提前 30 分钟提醒**，提醒走精确闹钟并做三档降级

### 课表
- 三种网格形态：只看工作日 / 显示周末 / 横向滑动
- 点空格子快速加课；**点已有课程直接改时间**（星期 / 节次 / 周次 / 单双周 / 教师 / 教室 / 删除）
- **按周查看**：`‹ 第 N 周 ›` 翻周，显示该周的日期区间，并列出这一周真实要上的课
- **拍照识别导入**：课表截图 → 离线 OCR → 按文字坐标重建网格 → 解析成候选课程 → 确认导入
- 支持清空 / 载入示例数据

### 手札
- 列表**搜索**（标题 / 正文模糊匹配）与附件类型筛选
- 六种输入：录音、相册、**直接拍照**、文件、表格、链接
- 详情页：正文编辑、图片九宫格、音频应用内播放、文件、**可编辑表格**、附件删除
- 图片识别取字，结果可追加进正文
- 正文自动保存（防抖）+ 顶栏手动保存按钮

### 其它
- **天气**：Open-Meteo（免注册免 Key）+ 系统定位，不内置城市表
- **背景**：8 套预设底色 / 相册图片 / 模糊 / 遮罩
- 深色模式；权限引导（含荣耀机型专项指引）

---

## 界面

`docs/设计原型.html` 是项目最初的高保真设计稿（15 屏），自包含单文件，浏览器直接打开：

```bash
start docs/设计原型.html        # Windows
open  docs/设计原型.html        # macOS
```

---

## 技术栈

| 层 | 选型 |
|---|---|
| 语言 / UI | Kotlin 2.0.21 · Jetpack Compose（单 Activity） |
| 依赖注入 | Hilt 2.53.1 |
| 本地存储 | Room 2.6.1（6 张表，含迁移）· DataStore 1.1.1 |
| 异步 | Coroutines 1.9.0 · Flow |
| 网络 | OkHttp 4.12.0 · kotlinx.serialization 1.7.3 |
| 解析 | Jsoup 1.18.3（教务页面） |
| OCR | ML Kit 中文离线模型（Hani_ctc） |
| 媒体 | Media3 1.5.1（ExoPlayer）· MediaRecorder |
| 图片加载 | Coil 3.0.4 |
| 后台 | WorkManager 2.10.0 |
| 构建 | AGP 8.7.3 · Gradle 8.9 · compileSdk 35 · **minSdk 26** |

**零第三方服务依赖**：不需要 API Key、不需要后端、不需要账号体系。

---

## 构建

### 方式一：GitHub Actions（不用配环境）

推到 `main` 或提交 PR 时自动跑单元测试并编译 Debug APK，产物在 Actions 页面的 **Artifacts** 里下载；
打 tag（如 `v1.3.0`）会自动创建 Release 并把 APK 作为附件。

### 方式二：本机

```bash
git clone https://github.com/guojiangzixi/summer-journal.git
cd summer-journal
./gradlew assembleDebug
# 产物：app/build/outputs/apk/debug/app-debug.apk
```

Windows 用 `gradlew.bat`。首次构建需要 Android SDK
（在 `local.properties` 里写 `sdk.dir=...`，该文件已被 `.gitignore` 排除）。

安装到手机：

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **首次安装后建议先做一件事**：进「我的」把通知 / 精确闹钟 / 后台运行三个权限配齐。
> 国产 ROM（尤其荣耀、小米）还要在「应用启动管理」里关掉自动管理，
> 否则日程提醒到点不会响 —— 这是这类 App 最容易被误判成 bug 的地方。

### 签名（发布正式包才需要）

```bash
cp keystore.properties.example keystore.properties   # 按注释填写
./gradlew assembleRelease
```

`keystore.properties` 与 `*.jks` 都在 `.gitignore` 里，**不会被提交**。

---

## 项目结构

```
app/src/main/java/com/summer/journal/
├── MainActivity.kt              入口 + 背景引擎
├── core/di/                     Hilt 装配
├── domain/
│   ├── model/                   领域模型（课程周期展开等纯逻辑）
│   └── holiday/                 节假日 + 农历算法
├── data/
│   ├── local/                   Room：实体 / DAO / Database（含迁移）
│   ├── datastore/               设置与背景主题持久化
│   ├── location/                定位 + 地名解析（无内置城市表）
│   ├── remote/weather/          Open-Meteo + 缓存降级
│   ├── remote/jwxt/             教务导入（WebView 登录 + Jsoup 解析）
│   ├── repo/                    Timetable / Calendar / Memo
│   ├── attachment/              AttachmentStore + RecordingService
│   ├── recognition/             ML Kit OCR
│   └── importing/               文字 → 课程解析
├── reminder/                    三档降级调度器 + 通知 + 开机重排
├── permission/                  权限定义 / 判断 / 厂商引导
└── ui/                          Compose 界面（theme / calendar / timetable / memo / settings / weather）
```

---

## 三个值得说的设计取舍

**1. 教务导入不逆向接口。**
用 WebView 让用户自己在学校官网登录，从 `CookieManager` 取 Cookie，再带 Cookie 请求课表页，
Jsoup **按内容特征**解析而不是按行号——这样教务改版不至于一改就崩。App 不存密码。

**2. 提醒不用 WorkManager。**
WorkManager 为了省电会合并任务，最多能晚十几分钟，对「上课前提醒」是致命的。
改用 `setExactAndAllowWhileIdle`，并做三档降级（精确 / 允许晚 10 分钟 / 仅应用内），
保证在权限不全的机器上也至少能用。

**3. 定位不依赖 GMS。**
国产机型大量没有 Google 服务，`FusedLocationProvider` 直接不可用。
改走系统 `LocationManager`：先读缓存位置（零耗电），拿不到再请求一次实时定位。

---

## 已知限制

这份清单是**逐条搜代码确认过调用链**的，不是估计——「文件存在」不等于「功能可用」。

- **日历只有月视图**。周 / 日 / 年视图组件已写在 `ui/calendar/CalendarViews.kt`
  （含分段切换 `ViewModeSegmented`、周条 `WeekBar`、日时间轴 `DayTimeline`、年网格 `YearMiniGrid`），
  **但未接入主界面**。
- **日程编辑面板未接入**。`EventEditorSheet`（重复规则 / 提醒 / 标签 / 颜色）已写好但未被调用；
  当前新建日程只能填标题、地点、小时，提醒固定为提前 30 分钟。
- **学期管理面板未接入**。`SemesterSheet` 已写好但未被调用；学期由程序自动创建，暂不能在界面上新建 / 切换 / 改周数。
- **教务一键导入**已从入口移除（底层代码保留），需要真实账号验证解析规则。
- **音频波形是装饰性的**，未接真实包络。
- **农历节日与调休数据未内置**（农历换算本身可用），需每年更新。

---

## 文档

| 文档 | 内容 |
|---|---|
| [开发文档](docs/开发文档.md) | 技术选型、架构、数据模型、里程碑 |
| [安装与验收](docs/安装与验收.md) | 装到手机 + 验收清单 + 报错排查 |
| [版本更新指南](docs/版本更新指南.md) | 改完代码怎么发新版（版本号 / 签名 / 数据库迁移） |
| [修复记录](docs/修复记录.md) | v1.3.0 各问题的根因与修复方式 |
| [问题记录](docs/问题记录.md) | 测试阶段发现的问题清单 |
| [覆盖事故与重建记录](docs/覆盖事故与重建记录.md) | 一次代码覆盖事故的完整复盘与预防措施 |
| [CHANGELOG](CHANGELOG.md) | 逐版本变更 |

---

## 许可

[MIT](LICENSE) © 2026 guojiangzixi
