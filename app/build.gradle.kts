plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt)
    alias(libs.plugins.hilt)
}

android {
    namespace = "br.com.exames.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = providers.gradleProperty("validationApplicationId").orElse("br.com.exames.offline").get()
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures { compose = true }
    androidResources { noCompress += listOf("litertlm", "traineddata") }
    sourceSets.getByName("main").assets.srcDir(layout.buildDirectory.dir("generated/model-assets"))
}
val bundledModelPath = providers.gradleProperty("examesModelPath")
    .orElse(rootProject.layout.projectDirectory.file("../.model-cache/gemma4-e2b/gemma-4-E2B-it-gpu.litertlm").asFile.absolutePath)
val stageBundledModel by tasks.registering(Exec::class) {
    inputs.file(bundledModelPath)
    inputs.files(rootProject.file("tools/litert/stage_model.py"), rootProject.file("validation/models/selection.json"))
    inputs.dir(rootProject.file("validation/models/gemma4-e2b"))
    outputs.dir(layout.buildDirectory.dir("generated/model-assets/models"))
    commandLine("python3", rootProject.file("tools/litert/stage_model.py"), "--model", bundledModelPath.get(),
        "--output", layout.buildDirectory.dir("generated/model-assets/models").get().asFile)
}
tasks.named("preBuild").configure { dependsOn(stageBundledModel) }
val corpusFixturePath = providers.gradleProperty("examesCorpusPath")
if (corpusFixturePath.isPresent) {
    val fixtureDir = layout.buildDirectory.dir("generated/corpus-test-assets")
    android.sourceSets.getByName("androidTest").assets.srcDir(fixtureDir)
    val stageCorpusFixture by tasks.registering(Exec::class) {
        inputs.file(corpusFixturePath)
        inputs.files(rootProject.file("tools/corpus/stage_android_fixture.py"), rootProject.file("tools/corpus/validate.py"))
        inputs.dir(rootProject.file("validation/corpus/schema"))
        outputs.file(fixtureDir.map { it.file("corpus.zip") })
        commandLine(providers.gradleProperty("examesCorpusPython").orElse("python3").get(), rootProject.file("tools/corpus/stage_android_fixture.py"),
            "--package", corpusFixturePath.get(), "--output", fixtureDir.get().file("corpus.zip").asFile)
    }
    tasks.named("preBuild").configure { dependsOn(stageCorpusFixture) }
}
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }
kapt { correctErrorTypes = true }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":domain"))
    implementation(libs.coroutines.android)
    implementation(project(":feature-import"))
    implementation(project(":feature-results"))
    implementation(project(":feature-history"))
    implementation(project(":data-documents"))
    implementation(project(":data-local"))
    implementation(project(":inference-local"))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    implementation(libs.hilt.android)
    kapt(libs.hilt.compiler)
    kapt(libs.kotlin.metadata)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(project(":validation-fixtures"))
    androidTestImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.room.runtime)
}
