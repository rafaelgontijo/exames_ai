plugins { alias(libs.plugins.android.library) }
android {
    namespace = "br.com.exames.pdflocal"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig { minSdk = 24 }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(libs.pdfium.android) {
        exclude(group = "io.legere", module = "pdfiumandroid-core")
    }
    implementation("br.com.exames.thirdparty:pdfiumandroid-core:2.0.1-exames2")
}
