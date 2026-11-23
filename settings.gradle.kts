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
        google(); mavenCentral()
    }
}
rootProject.name = "ExamesOffline"
include(":domain", ":core-ui", ":data-private-pdf", ":pdf-local", ":inference-local", ":app", ":ui-checks")
// Isolated technical gate; no dependency from the application.
include(":pdf-spike")
