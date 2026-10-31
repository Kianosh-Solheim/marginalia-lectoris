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

plugins { id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0" }

dependencyResolutionManagement {
  repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
  repositories {
    google()
    mavenCentral()
    maven { url = uri("https://jitpack.io"); content { includeGroupByRegex("com\\.github.*") } }
    // BOOX pen SDK (fast pen drawing on BOOX e-ink tablets). Only Onyx's own groups are taken from it.
    maven {
      url = uri("http://repo.boox.com/repository/maven-public/")
      isAllowInsecureProtocol = true
      content { includeGroupByRegex("com\\.onyx.*") }
    }
    // Old libraries the BOOX SDK needs that are no longer on Maven Central (served by BOOX's proxy).
    maven {
      url = uri("http://repo.boox.com/repository/proxy-public/")
      isAllowInsecureProtocol = true
    }
  }
}

rootProject.name = "Marginalia Lectoris"

include(":app")
