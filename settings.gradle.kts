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

// docs/03 §9.2.1: 解決順は公式先頭 → Rokid → aliyun fallback 末尾 (CI 502 事故回避)。
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "claude-mobile-hud"

// docs/03 §9.2: :cxrglobal:lib は repo root の cxrglobal/ submodule を Android library で取り込む。
include(":protocol")
include(":phone")
include(":glass")
include(":cxrglobal:lib")
project(":cxrglobal:lib").projectDir = file("cxrglobal/lib")
