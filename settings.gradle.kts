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
        // 通常解決 (公式 / 一次ソース) を先頭に。CI runner (US) では aliyun を先頭に
        // 置くと 502 Bad Gateway で Gradle が repo 全体を `disable on error` 扱いにし、
        // 他の repo にもフォールバックしないまま build 不能になる事故が発生する。
        google()
        mavenCentral()
        // :cxrglobal:lib が依存する com.rokid.cxr:client-l を取りに行くための rokid maven。
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        // 中国環境からの google() アクセスが阻害される場合の保険 fallback (最後)。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
    }
}

rootProject.name = "claude-mobile-hud"

include(":protocol")
include(":phone")
include(":glass")
// cxrglobal submodule の :lib を Android library として取り込み (Phase 4c1)。
include(":cxrglobal:lib")
project(":cxrglobal:lib").projectDir = file("cxrglobal/lib")
