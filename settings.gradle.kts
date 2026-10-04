pluginManagement {
    repositories {
        // 国内镜像优先 —— Google Maven / Maven Central 在国内经常连不上，
        // 镜像放在前面可以让首次 Sync 从 15 分钟缩到 2 分钟。
        // 官方源保留在后面兜底，镜像没有的构件会自动回落到官方。
        maven("https://maven.aliyun.com/repository/gradle-plugin")
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

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
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        maven("https://maven.aliyun.com/repository/google")
        maven("https://maven.aliyun.com/repository/public")

        google()
        mavenCentral()
    }
}

rootProject.name = "SummerJournal"
include(":app")
