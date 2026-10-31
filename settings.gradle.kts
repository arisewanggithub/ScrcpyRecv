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
rootProject.name = "ScreenCopyReceiver"
include(":app")
if (providers.gradleProperty("releaseSmokeTest").getOrElse("false") == "true") {
    include(":release-check")
}
