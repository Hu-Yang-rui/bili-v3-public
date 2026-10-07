plugins {
    alias(libs.plugins.android.application) apply false
    // 🔴 保留 kotlin.android：本项目用 KSP，必须关掉 AGP 9 的内置 Kotlin。
    // 详见 app/build.gradle.kts 的 plugins 块与 gradle.properties。
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
