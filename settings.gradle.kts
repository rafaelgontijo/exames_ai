pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "ExamesOffline"
include(":domain", ":data-documents", ":data-local", ":feature-import", ":feature-results", ":feature-history", ":inference-experimental", ":app")
