import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.lsp.edit.example.Main_jvmKt"
        }
    }

    macosArm64 {
        binaries.executable()
    }
    macosX64 {
        binaries.executable()
    }

    linuxX64 {
        binaries.executable()
    }
    linuxArm64 {
        binaries.executable()
    }

    mingwX64 {
        binaries.executable()
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":examples:common"))
            implementation(project(":lsp-edit"))
            implementation(libs.imgui.kmp)
            implementation(libs.lsp.kmp)
            implementation(libs.sdl.kmp)
            implementation(libs.kotlinx.coroutines.core)
            implementation(libs.kotlinx.serialization.json)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

// SDL3 (via LWJGL on the JVM) must run on the first thread on macOS,
// otherwise video driver init fails. --enable-native-access silences the
// LWJGL JVM warnings.
tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
