plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val modelAssets = layout.projectDirectory.dir("build/generated/model-assets")
val validationApplicationId = providers.gradleProperty("validationApplicationId")
val bundleLocalModel = providers.gradleProperty("bundleLocalModel").map { it.equals("true", ignoreCase = true) }.orElse(false)
val cloudFallbackUrl = providers.gradleProperty("examesCloudFallbackUrl").orElse("")
val cloudAuthToken = providers.gradleProperty("examesCloudAuthToken").orElse("")
val cognitoRegion = providers.gradleProperty("examesCognitoRegion").orElse("")
val cognitoClientId = providers.gradleProperty("examesCognitoClientId").orElse("")
val cognitoRefreshToken = providers.gradleProperty("examesCognitoRefreshToken").orElse("")
val modelDownloadUrl = providers.gradleProperty("examesModelDownloadUrl").orElse(
    "https://huggingface.co/litert-community/gemma-4-E2B-it-litert-lm/resolve/2e12bfd869b2962984f97a1f2a26807316d534cc/gemma-4-E2B-it.litertlm",
)
if (validationApplicationId.isPresent) {
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
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
        buildConfigField("String", "CLOUD_FALLBACK_URL", "\"${cloudFallbackUrl.get()}\"")
        buildConfigField("String", "CLOUD_AUTH_TOKEN", "\"${cloudAuthToken.get()}\"")
        buildConfigField("String", "COGNITO_REGION", "\"${cognitoRegion.get()}\"")
        buildConfigField("String", "COGNITO_CLIENT_ID", "\"${cognitoClientId.get()}\"")
        buildConfigField("String", "COGNITO_REFRESH_TOKEN", "\"${cognitoRefreshToken.get()}\"")
        buildConfigField("String", "MODEL_DOWNLOAD_URL", "\"${modelDownloadUrl.get()}\"")
    }
    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    androidResources { noCompress += listOf("litertlm") }
    if (bundleLocalModel.get()) {
        sourceSets.getByName("main").assets.srcDir(modelAssets)
    }
}

val bundledModelPath = providers.gradleProperty("examesModelPath")
    .orElse(rootProject.layout.projectDirectory.file("../.model-cache/gemma4-e2b-mm/gemma-4-E2B-it.litertlm").asFile.absolutePath)
val stageBundledModel by tasks.registering(Exec::class) {
    onlyIf { bundleLocalModel.get() }
    inputs.file(bundledModelPath)
    inputs.files(rootProject.file("tools/litert/stage_model.py"), rootProject.file("validation/models/selection.json"))
    inputs.dir(rootProject.file("validation/models/gemma4-e2b"))
    outputs.dir(modelAssets.dir("models"))
    commandLine(
        "python3",
        rootProject.file("tools/litert/stage_model.py"),
        "--model",
        bundledModelPath.get(),
        "--output",
        modelAssets.dir("models").asFile,
    )
}
if (bundleLocalModel.get()) {
    tasks.named("preBuild").configure { dependsOn(stageBundledModel) }
}

kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":domain"))
    implementation(project(":core-ui"))
    implementation(project(":data-private-pdf"))
    implementation(project(":inference-local"))
    implementation(project(":inference-cloud"))
    implementation(libs.coroutines.android)
    implementation(libs.androidx.core)
    implementation(libs.androidx.work.runtime)
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
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.kotlinx.serialization.json)
}
