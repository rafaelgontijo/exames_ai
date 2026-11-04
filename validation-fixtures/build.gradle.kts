plugins { alias(libs.plugins.android.library) }
android {
    namespace = "br.com.exames.fixtures"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
