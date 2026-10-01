pluginManagement {
    repositories {
        // 国内直连 google()/mavenCentral() 会超时，本地默认走阿里云镜像。
        // ⚠️ CI 必须设 BILIV3_REPO_MIRROR=off 关掉镜像：
        //    阿里云对海外 runner 偶发 502，而 Gradle 对 **5xx 是硬失败**
        //    （只有 404 才会继续试下一个仓库），一旦 502 整个构建直接挂，
        //    不会回落到 google()/mavenCentral()。
        if (System.getenv("BILIV3_REPO_MIRROR") != "off") {
            maven { url = uri("https://maven.aliyun.com/repository/gradle-plugin") }
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        if (System.getenv("BILIV3_REPO_MIRROR") != "off") {
            maven { url = uri("https://maven.aliyun.com/repository/google") }
            maven { url = uri("https://maven.aliyun.com/repository/public") }
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "BiliV3"
include(":app")
