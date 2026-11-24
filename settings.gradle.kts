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
        // Artifex's repository for the MuPDF Android library (com.artifex.mupdf:fitz).
        maven("https://maven.ghostscript.com") {
            content { includeGroup("com.artifex.mupdf") }
        }
    }
}

rootProject.name = "ThorReader"
include(":app")
