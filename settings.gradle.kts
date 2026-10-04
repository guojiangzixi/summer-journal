// ══════════════════════════════════════════════════════════════
//  依赖源策略
//
//  本地（国内）默认走阿里云镜像 —— Google Maven / Maven Central
//  在国内经常连不上，镜像放在前面可以让首次 Sync 从十几分钟缩到两分钟。
//
//  CI 上 GitHub 的 runner 在海外，阿里云镜像反而是绕远路，
//  官方源（google / mavenCentral / gradlePluginPortal）更快而且不会缺构件。
//
//  用环境变量切换，不改代码：
//    · 默认（本地）        → 用镜像
//    · CI=true（GitHub）   → 用官方源
//    · 想手动指定时设 USE_CHINA_MIRROR=true / false
// ══════════════════════════════════════════════════════════════

pluginManagement {
    val useChinaMirror = System.getenv("USE_CHINA_MIRROR")?.toBoolean()
        ?: (System.getenv("CI") == null)

    repositories {
        if (useChinaMirror) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }

        // 官方源始终保留，镜像没有的构件自动回落
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    val useChinaMirror = System.getenv("USE_CHINA_MIRROR")?.toBoolean()
        ?: (System.getenv("CI") == null)

    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (useChinaMirror) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }

        google()
        mavenCentral()
    }
}

rootProject.name = "SummerJournal"
include(":app")
