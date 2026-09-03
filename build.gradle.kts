// 根 build.gradle.kts
// 所有插件都在版本目录 (libs.versions.toml) 里声明，这里只做 apply false
// 让各子 module 自己按需启用。Android 端（sender/receiver）只需要 android + kotlin-android。
// Desktop 端只需要 kotlin-multiplatform + compose-multiplatform + kotlin-serialization。
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.multiplatform) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.compose.multiplatform) apply false
    alias(libs.plugins.shadow) apply false
}
