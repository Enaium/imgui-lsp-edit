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
            implementation(libs.sdl.kmp)
        }
    }
}

// SDL3 (via LWJGL on the JVM) must run on the first thread on macOS,
// otherwise video driver init fails and no window appears.
tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
