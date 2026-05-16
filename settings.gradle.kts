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

rootProject.name = "claude-mobile-hud"

include(":protocol")
// Phase 4 で Android Studio から scaffold した後、以下を有効化:
// include(":phone")
// include(":glass")
// include(":cxrglobal")  ← submodule をライブラリとして取り込む場合
