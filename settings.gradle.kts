pluginManagement {
    repositories {
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
        google()
        mavenCentral()
    }
}

rootProject.name = "Nice-Proxy"

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")

include(":app")

// 纯 JVM 模块：不依赖 Android SDK，可用普通 JUnit 快速跑单测
include(":core:model")
include(":core:common")
include(":core:config")

// Android 库模块
include(":core:designsystem")
include(":core:database")
include(":core:datastore")
include(":core:network")
include(":core:data")
include(":core:service")

// feature:* 模块在 M2 阶段从 :app 中拆出（目前放在 :app 的 feature 包下），
// 见 docs/DESIGN.md §5.2
