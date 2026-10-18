// Top-level build file. Plugin versions are declared once in gradle/libs.versions.toml
// and applied per-module so that every module resolves identical versions.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
}
