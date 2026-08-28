pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.10.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // karoo-ext is only published to GitHub Packages. Downloading from it
        // requires a GitHub personal access token with the "read:packages"
        // scope, even though the repo itself is public. Put these two lines
        // in ~/.gradle/gradle.properties (NOT this project's gradle.properties,
        // so the token never ends up committed to a repo):
        //   gpr.user=<your GitHub username>
        //   gpr.key=<your token>
        maven {
            url = uri("https://maven.pkg.github.com/hammerheadnav/karoo-ext")
            credentials {
                username = providers.gradleProperty("gpr.user").getOrElse(System.getenv("USERNAME") ?: "")
                password = providers.gradleProperty("gpr.key").getOrElse(System.getenv("TOKEN") ?: "")
            }
        }
    }
}

rootProject.name = "karoo-insta360"
include(":app")
