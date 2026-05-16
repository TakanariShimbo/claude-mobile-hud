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
        // :cxrglobal:lib が依存する com.rokid.cxr:client-l を取りに行くための rokid maven。
        // 既知の supply-chain 注意: rokid.com / aliyun のミラーが落ちると build 不能になる
        // が、4c1 で取り込んだ後は CI cache で十分回避可。
        maven { url = uri("https://maven.aliyun.com/repository/google") }
        maven { url = uri("https://maven.rokid.com/repository/maven-public/") }
        google()
        mavenCentral()
    }
}

rootProject.name = "claude-mobile-hud"

include(":protocol")
include(":phone")
include(":glass")
// cxrglobal submodule の :lib を Android library として取り込み (Phase 4c1)。
include(":cxrglobal:lib")
project(":cxrglobal:lib").projectDir = file("cxrglobal/lib")
