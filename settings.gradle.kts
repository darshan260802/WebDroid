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
        // Guardian Project's Maven repo: tor-android (Tor for the per-site VPN).
        maven {
            url = uri("https://raw.githubusercontent.com/guardianproject/gpmaven/master")
            content { includeModule("info.guardianproject", "tor-android") }
        }
    }
}

rootProject.name = "WebDroid"
include(":app")
