// 本地（国内）默认优先用阿里云镜像；CI 环境下走官方仓库，避免镜像在境外 runner 上不可达。
// 注意：pluginManagement 块内看不到脚本级函数/变量，所以这里直接内联读环境变量。
pluginManagement {
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            maven("https://maven.aliyun.com/repository/gradle-plugin")
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories {
        if (System.getenv("CI").isNullOrEmpty()) {
            maven("https://maven.aliyun.com/repository/google")
            maven("https://maven.aliyun.com/repository/public")
        }
        google()
        mavenCentral()
    }
}

rootProject.name = "DeepSeekChat"
include(":app")
