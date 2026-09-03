plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.android.kmp)
    alias(libs.plugins.maven.publish)
}

group = rootProject.group
version = rootProject.version

repositories {
    mavenCentral()
    google()
}

kotlin {
    jvmToolchain(25)

    // JVM
    jvm()

    // Apple: macOS
    macosX64()
    macosArm64()

    // Apple: iOS
    iosX64()
    iosArm64()
    iosSimulatorArm64()

    // Apple: tvOS (imgui-kmp publishes arm64 + simulator only)
    tvosArm64()
    tvosSimulatorArm64()

    // Apple: watchOS (imgui-kmp publishes arm64/device/simulator, not x64)
    watchosArm64()
    watchosSimulatorArm64()
    watchosDeviceArm64()

    // Linux
    linuxX64()
    linuxArm64()

    // Windows
    mingwX64()

    android {
        namespace = "cn.enaium.lsp.edit"
        // imgui-kmp's Android AAR is compiled against API 37.
        compileSdk = 37
        minSdk = 24
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.imgui.kmp)
            implementation(libs.lsp.kmp)
        }
        commonTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
        }
        jvmMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.kotlinx.coroutines.test)
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
        androidMain.dependencies {
            implementation(libs.kotlinx.coroutines.core)
        }
    }
}

// ===== Publishing (com.vanniktech.maven.publish) =====
mavenPublishing {
    coordinates("cn.enaium.imgui", "lsp-edit", project.version.toString())

    pom {
        name = "lsp-edit"
        description = "A Langauge Server Protocol (LSP) driven code editor widget for Kotlin Multiplatform, rendered with Dear ImGui via imgui-kmp, powered by lsp-kmp."
        url = "https://github.com/Enaium/lsp-edit"
        licenses {
            license {
                name = "MIT License"
                url = "https://spdx.org/licenses/MIT.html"
            }
        }
        developers {
            developer {
                id = "enaium"
                name = "Enaium"
                url = "https://github.com/Enaium"
            }
        }
        scm {
            url = "https://github.com/Enaium/lsp-edit"
            connection = "scm:git:git@github.com:Enaium/lsp-edit.git"
            developerConnection = "scm:git:git@github.com:Enaium/lsp-edit.git"
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}
