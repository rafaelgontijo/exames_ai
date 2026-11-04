plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "br.com.exames.datadocuments"
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
    // The instrumentation APK otherwise inherits minSdk as targetSdk; Android 17 shows an "older Android" dialog over tests.
    testOptions { targetSdk = 36 }
    androidResources { noCompress += "traineddata" }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/ocr-assets"))
}
val ocrArtifact = configurations.detachedConfiguration(dependencies.create("cz.adaptech.tesseract4android:tesseract4android:4.9.0@aar")).apply { isTransitive = false }
val ocrModelPath = providers.gradleProperty("examesOcrModelPath").orElse(rootProject.file("../.model-cache/ocr-por/por.traineddata").absolutePath)
val stageOcr by tasks.registering(Exec::class) {
    inputs.files(ocrArtifact)
    inputs.file(ocrModelPath)
    inputs.file(rootProject.file("tools/ocr/stage.py"))
    inputs.dir(rootProject.file("validation/ocr"))
    outputs.dir(layout.buildDirectory.dir("generated/ocr-assets/ocr"))
    doFirst {
        commandLine("python3", rootProject.file("tools/ocr/stage.py"), "--aar", ocrArtifact.singleFile,
            "--model", ocrModelPath.get(), "--output", layout.buildDirectory.dir("generated/ocr-assets/ocr").get().asFile)
    }
}
tasks.named("preBuild").configure { dependsOn(stageOcr) }
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":domain"))
    implementation(project(":pdf-local"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
    implementation(libs.tesseract.ocr)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.lifecycle.runtime)
}
