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
        maven("https://download.linphone.org/releases/android/maven_repository/") {
            content { includeGroupByRegex("org\\.linphone(\\..*)?") }
        }
    }
}
rootProject.name = "Sippy"
include(":app")
