import org.gradle.internal.os.OperatingSystem
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
}

// tree-sitter language modules published to Maven Central by the
// tree-sitter-languages-kmp project. Each artifact bundles the grammar's
// native library for the current platform.
val treeSitterLanguages = mapOf(
    "agda" to "0.25.1",
    "bash" to "0.25.1",
    "c" to "0.25.1",
    "c-sharp" to "0.25.1",
    "cpp" to "0.25.1",
    "css" to "0.25.1",
    "diff" to "0.2.0",
    "embedded-template" to "0.25.1",
    "glsl" to "0.2.0",
    "go" to "0.25.1",
    "haskell" to "0.25.1",
    "html" to "0.25.1",
    "java" to "0.25.1",
    "javascript" to "0.25.1",
    "json" to "0.25.1",
    "julia" to "0.25.1",
    "kotlin" to "0.3.8",
    "lua" to "0.5.0",
    "markdown" to "0.5.3",
    "ocaml" to "0.25.1",
    "php" to "0.25.1",
    "properties" to "0.3.0",
    "python" to "0.25.1",
    "regex" to "0.25.1",
    "ruby" to "0.25.1",
    "rust" to "0.25.1",
    "scala" to "0.25.1",
    "smali" to "1.0.0",
    "toml" to "0.7.0",
    "tsx" to "0.25.1",
    "typescript" to "0.25.1",
    "verilog" to "0.25.1",
    "xml" to "0.7.0",
    "yaml" to "0.7.2",
)

kotlin {
    jvm {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_21)
        }
        mainRun {
            mainClass = "cn.enaium.lsp.edit.example.Main_jvmKt"
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(project(":examples:common"))
            implementation(project(":lsp-edit"))
            implementation(libs.imgui.kmp)
            implementation(libs.sdl.kmp)
            implementation("io.github.tree-sitter:ktreesitter:0.25.1")
            treeSitterLanguages.forEach { (name, version) ->
                implementation("cn.enaium.treesitter:treesitter-languages-$name-kmp:$version")
            }
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}

tasks.withType<Test>().configureEach {
    useJUnitPlatform()
}

// SDL3 (via LWJGL on the JVM) must run on the first thread on macOS,
// otherwise video driver init fails. --enable-native-access silences the
// LWJGL JVM warnings.
tasks.withType(JavaExec::class.java).configureEach {
    if (OperatingSystem.current().isMacOsX && name == "jvmRun") {
        jvmArgs("--enable-native-access=ALL-UNNAMED", "-XstartOnFirstThread")
    }
}
