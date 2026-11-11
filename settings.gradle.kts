pluginManagement { repositories { google(); mavenCentral(); gradlePluginPortal() } }
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google(); mavenCentral()
        // Only for -Plumora.cameraunit=true. Read-only account that OPPO publishes for third-party
        // developers in its official demo (github.com/oppo/CameraUnit, build.gradle).
        if (providers.gradleProperty("lumora.cameraunit").orNull == "true") {
            maven {
                url = uri("https://maven.columbus.heytapmobi.com/repository/OpenCapability/")
                credentials { username = "ocuser"; password = "3dca1351358f49aeaf5af516ffd05a7c8e0cbd56" }
                content { includeGroup("com.oplus.ocs") }
            }
        }
    }
}
rootProject.name = "LumaCamera"
include(":app")
