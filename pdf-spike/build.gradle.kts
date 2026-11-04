plugins { alias(libs.plugins.android.library) }
val patchedJni = providers.gradleProperty("pdfiumPatchedJni").map(String::toBoolean).getOrElse(false)
val correctedCore = providers.gradleProperty("pdfiumCoreAar").map { file(it) }
    .orElse(layout.buildDirectory.file("native-rebuild/pdfiumandroid-core-2.0.1-exames1.aar").map { it.asFile })

android {
    namespace = "br.com.exames.pdfspike"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 24
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    androidTestImplementation(project(":data-local")) { exclude(module = "pdf-local") }
    androidTestImplementation(project(":validation-fixtures"))
    androidTestImplementation(libs.pdfium.android) {
        if (patchedJni) exclude(group = "io.legere", module = "pdfiumandroid-core")
    }
    // Explicit for the Java constructor; Kotlin default-argument overloads are synthetic.
    if (patchedJni) androidTestImplementation(files(correctedCore))
    else androidTestImplementation(libs.pdfium.android.core)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
}
if (patchedJni) {
    tasks.matching { it.name == "preDebugAndroidTestBuild" }.configureEach {
        doFirst { check(correctedCore.get().isFile) { "Run tools/pdf/rebuild_jni.py before building the corrected spike" } }
    }
}
