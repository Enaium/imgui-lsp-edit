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

    sourceSets {
        commonMain.dependencies {
            implementation(project(":examples:common"))
            implementation(project(":lsp-edit"))
            implementation(libs.imgui.kmp)
            implementation(libs.sdl.kmp)
            // Tree-sitter grammars; versions come from the version catalog
            // (tree-sitter-languages-kmp README table).
            implementation(libs.ktreesitter)
            implementation(libs.treesitter.agda)
            implementation(libs.treesitter.bash)
            implementation(libs.treesitter.c)
            implementation(libs.treesitter.c.sharp)
            implementation(libs.treesitter.cpp)
            implementation(libs.treesitter.css)
            implementation(libs.treesitter.diff)
            implementation(libs.treesitter.embedded.template)
            implementation(libs.treesitter.glsl)
            implementation(libs.treesitter.go)
            implementation(libs.treesitter.haskell)
            implementation(libs.treesitter.html)
            implementation(libs.treesitter.java)
            implementation(libs.treesitter.javascript)
            implementation(libs.treesitter.json)
            implementation(libs.treesitter.julia)
            implementation(libs.treesitter.kotlin)
            implementation(libs.treesitter.lua)
            implementation(libs.treesitter.markdown)
            implementation(libs.treesitter.ocaml)
            implementation(libs.treesitter.php)
            implementation(libs.treesitter.properties)
            implementation(libs.treesitter.python)
            implementation(libs.treesitter.regex)
            implementation(libs.treesitter.ruby)
            implementation(libs.treesitter.rust)
            implementation(libs.treesitter.scala)
            implementation(libs.treesitter.smali)
            implementation(libs.treesitter.toml)
            implementation(libs.treesitter.tsx)
            implementation(libs.treesitter.typescript)
            implementation(libs.treesitter.verilog)
            implementation(libs.treesitter.xml)
            implementation(libs.treesitter.yaml)
        }
        jvmTest.dependencies {
            implementation(kotlin("test"))
            implementation(libs.junit.jupiter)
            runtimeOnly(libs.junit.platform.launcher)
        }
    }
}
