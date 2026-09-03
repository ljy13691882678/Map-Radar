import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.shadow)
}

kotlin {
    jvm()
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                // compose.desktop.currentOs 会自动引入 Compose Desktop runtime + 当前 OS 的 native 依赖
                // compose.material3 会 pull in compose.ui + compose.ui-graphics + compose.material3 + compose.material-icons
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)

                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Compose Desktop 原生打包（Windows: .exe/.msi / macOS: .dmg / Linux: .deb/.rpm）
compose.desktop {
    application {
        mainClass = "com.unicorn.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Exe)
            packageName = "UnicornDesktopReceiver"
            version = "0.1.0"
        }
    }
}

// shadowJar —— 为 Windows 用户打一个 fat-jar，便于 java -jar 直接跑
// compose.desktop 自己有 distZip，但我们要更轻的 fat-jar
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    archiveBaseName.set("unicorn-desktop-receiver")
    archiveClassifier.set("all")
    archiveVersion.set("0.1.0")
    manifest {
        attributes["Main-Class"] = "com.unicorn.desktop.MainKt"
    }
    mergeServiceFiles()
}
