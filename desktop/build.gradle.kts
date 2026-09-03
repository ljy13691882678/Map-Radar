// 注意：Compose Desktop 原生打包 (MSI/EXE/DMG) 需要在对应 OS 的 runner 上执行：
//   - .msi / .exe → windows-latest（WiX v3 已预装）
//   - .dmg        → macos-latest
//   - .deb / .rpm → ubuntu-latest
// 本项目的 CI 里 desktop job 已经切到 windows-latest，所以 exe/msi 能直接产出。
// 见 .github/workflows/build.yml 里的 desktop job (runs-on: windows-latest)。

import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // compose.desktop.currentOs 自动引入 Compose Desktop runtime + 当前 OS native 依赖
                // compose.material3 会 pull in compose.ui + compose.ui-graphics + compose.material3 + compose.material-icons
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// ============ Compose Desktop 原生打包 (EXE / MSI) ============
compose.desktop {
    application {
        mainClass = "com.unicorn.desktop.MainKt"

        nativeDistributions {
            targetFormats(TargetFormat.Msi, TargetFormat.Exe)
            packageName = "UnicornDesktopReceiver"
            packageVersion = "1.1.0"   // MAJOR 必须 > 0（Compose Desktop hard requirement）
        }
    }
}

// ============ 额外：fat-jar（兜底，跨平台 java -jar） ============
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat jar with all runtime dependencies (java -jar unicorn-desktop-receiver-fat.jar)"

    archiveBaseName.set("unicorn-desktop-receiver")
    archiveClassifier.set("fat")
    archiveVersion.set("1.1.0")

    manifest { attributes["Main-Class"] = "com.unicorn.desktop.MainKt" }

    val mainCompilation = kotlin.jvm().compilations["main"]
    from(mainCompilation.output)
    from(mainCompilation.runtimeDependencyFiles)

    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
