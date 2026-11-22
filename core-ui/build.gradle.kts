plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/font-licenses"))
    namespace = "br.com.exames.coreui"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    // The instrumentation APK otherwise inherits minSdk as targetSdk; Android 17 shows an "older Android" dialog over tests.
    testOptions { targetSdk = 36 }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

// Deliberately no dependency on :domain, network, or telemetry libraries.
dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    testImplementation(libs.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.core)
}


val stageFontLicense by tasks.registering(Copy::class) {
    from("third_party/figtree/OFL.txt")
    into(layout.buildDirectory.dir("generated/font-licenses/licenses"))
    rename("OFL.txt", "figtree-OFL.txt")
}
tasks.named("preBuild").configure { dependsOn(stageFontLicense) }
