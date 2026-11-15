// 핫스팟 프록시: 핫스팟에 붙은 기기가 폰의 모바일 데이터를 쓰게 하는 HTTP·SOCKS5 proxy 앱.
pluginManagement {
    repositories {
        google()
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

rootProject.name = "android-proxy"
include(":app")
