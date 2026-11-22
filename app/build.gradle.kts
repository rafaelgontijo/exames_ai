plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

val modelAssets = layout.projectDirectory.dir("build/generated/model-assets")
val legacyWorkbench = providers.gradleProperty("legacyWorkbench").map(String::toBoolean).orElse(false).get()

val validationApplicationId = providers.gradleProperty("validationApplicationId")
if (legacyWorkbench) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/workbench"))
    apply(plugin = "org.jetbrains.kotlin.kapt")
    apply(plugin = "com.google.dagger.hilt.android")
    extensions.configure<org.jetbrains.kotlin.gradle.plugin.KaptExtension> { correctErrorTypes = true }
} else if (validationApplicationId.isPresent) {
    layout.buildDirectory.set(layout.projectDirectory.dir("build/validation"))
}

android {
    namespace = "br.com.exames.app"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    defaultConfig {
        minSdk = 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        applicationId = validationApplicationId.orElse("br.com.exames.offline").get()
        manifestPlaceholders["workbenchFixtureAuthority"] = providers.gradleProperty("workbenchFixtureAuthority")
            .orElse("br.com.exames.validation.workbench.synthetic-import").get()
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
    if (legacyWorkbench) {
        sourceSets.getByName("main").apply {
            kotlin.setSrcDirs(listOf("src/workbench/kotlin"))
            manifest.srcFile("src/workbench/AndroidManifest.xml")
        }
        sourceSets.getByName("test").kotlin.srcDir("src/workbenchUnitTest/kotlin")
        sourceSets.getByName("androidTest").apply {
            java.srcDir("src/workbenchAndroidTest/java")
            kotlin.srcDir("src/workbenchAndroidTest/kotlin")
            manifest.srcFile("src/workbenchAndroidTest/AndroidManifest.xml")
            assets.srcDir(rootProject.file("validation/development/multipage"))
        }
    } else {
        sourceSets.getByName("main").kotlin.srcDir("src/extraction/kotlin")
        sourceSets.getByName("androidTest").apply {
            java.setSrcDirs(listOf("src/extractionTest/java"))
            kotlin.setSrcDirs(listOf("src/extractionTest/kotlin"))
            manifest.srcFile("src/extractionTest/AndroidManifest.xml")
        }
    }
    sourceSets.getByName("androidTest").java.srcDir("src/sharedTest/java")
    androidResources { noCompress += if (legacyWorkbench) listOf("litertlm", "traineddata") else listOf("litertlm") }
    sourceSets.getByName("main").assets.srcDir(modelAssets)
}
val bundledModelPath = providers.gradleProperty("examesModelPath")
    .orElse(rootProject.layout.projectDirectory.file("../.model-cache/gemma4-e2b/gemma-4-E2B-it-gpu.litertlm").asFile.absolutePath)
val stageBundledModel by tasks.registering(Exec::class) {
    inputs.file(bundledModelPath)
    inputs.files(rootProject.file("tools/litert/stage_model.py"), rootProject.file("validation/models/selection.json"))
    inputs.dir(rootProject.file("validation/models/gemma4-e2b"))
    outputs.dir(modelAssets.dir("models"))
    commandLine("python3", rootProject.file("tools/litert/stage_model.py"), "--model", bundledModelPath.get(),
        "--output", modelAssets.dir("models").asFile)
}
tasks.named("preBuild").configure { dependsOn(stageBundledModel) }
val corpusFixturePath = providers.gradleProperty("examesCorpusPath")
if (legacyWorkbench && corpusFixturePath.isPresent) {
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

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":domain"))
    implementation(project(":core-ui"))
    implementation(libs.coroutines.android)
    if (legacyWorkbench) {
        implementation(project(":feature-import"))
        implementation(project(":feature-results"))
        implementation(project(":feature-history"))
        implementation(project(":data-documents"))
        implementation(project(":data-local"))
        implementation(libs.androidx.navigation.compose)
        implementation(libs.hilt.android)
        add("kapt", libs.hilt.compiler)
        add("kapt", libs.kotlin.metadata)
        androidTestImplementation(project(":validation-fixtures"))
        androidTestImplementation(libs.room.runtime)
    } else {
        implementation(project(":data-private-pdf"))
    }
    implementation(project(":inference-local"))
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    debugImplementation(libs.compose.ui.tooling)
    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    debugImplementation(libs.compose.ui.test.manifest)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.serialization.json)
}
