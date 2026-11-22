plugins {
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.kapt) apply false
    alias(libs.plugins.hilt) apply false
}

tasks.register("verifyDexerVersion") {
    doLast {
        val version = Class.forName("com.android.tools.r8.Version")
            .getMethod("getVersionString").invoke(null).toString()
        check(version.substringBefore(' ') == "9.1.31") { "Unexpected D8/R8 version: $version" }
        println("D8/R8 verified: $version")
    }
}
