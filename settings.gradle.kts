pluginManagement {
    buildscript {
        repositories { google(); mavenCentral() }
        // LiteRT-LM 0.18.1 carries Kotlin 2.4 metadata; 9.1.31 is published in Google Maven.
        dependencies { classpath("com.android.tools:r8:9.1.31") }
    }
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        exclusiveContent {
            forRepository { maven { url = uri("build/pdfium-repository") } }
            filter { includeGroup("br.com.exames.thirdparty") }
        }
        exclusiveContent {
            forRepository { maven { url = uri("https://jitpack.io") } }
            filter { includeGroup("cz.adaptech.tesseract4android") }
        }
        google(); mavenCentral()
    }
}
rootProject.name = "ExamesOffline"
include(":domain", ":data-documents", ":data-local", ":feature-import", ":feature-results", ":feature-history", ":inference-local", ":core-ui", ":ui-checks", ":app")
// Isolated technical gate; no dependency from the application.
include(":pdf-spike")
include(":pdf-local")
include(":validation-fixtures")
