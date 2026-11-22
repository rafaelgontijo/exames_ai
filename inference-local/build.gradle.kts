import java.security.MessageDigest

plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "br.com.exames.inferencelocal"
    testNamespace = "br.com.exames.inferenceexperimental"
    compileSdk = 36
    buildToolsVersion = "36.0.0"
    testOptions.unitTests.all { testTask ->
        testTask.systemProperty("exames.projectRoot", rootProject.projectDir.absolutePath)
        testTask.inputs.files(rootProject.file("validation/models/selection.json"),
            rootProject.file("validation/models/gemma4-e2b/model.lock.json"))
        testTask.doFirst {
            val variant = if (testTask.name.contains("Release")) "release" else "debug"
            val artifact = configurations.getByName("${variant}RuntimeClasspath").resolvedConfiguration.resolvedArtifacts.single {
                it.moduleVersion.id.group == "com.google.ai.edge.litertlm" && it.name == "litertlm-android"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            artifact.file.inputStream().use { input ->
                val buffer = ByteArray(8192)
                while (true) {
                    val read = input.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            testTask.systemProperty("exames.runtimeCoordinate", artifact.moduleVersion.id.toString())
            testTask.systemProperty("exames.runtimeAarSha256", digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) })
        }
    }
    defaultConfig {
        testApplicationId = "br.com.exames.inferenceexperimental.test"
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
kotlin { compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) } }

dependencies {
    coreLibraryDesugaring(libs.desugar.jdk)
    implementation(project(":domain"))
    implementation(libs.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    testImplementation(libs.junit4)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.kotlinx.serialization.json)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    implementation(libs.litertlm.android)
}
