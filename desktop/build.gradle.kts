import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.compose.multiplatform)
    alias(libs.plugins.shadow)
}

kotlin {
    jvm()

    // JVM 目标，和 Android 一样用 Java 17
    jvmToolchain(17)

    sourceSets {
        val jvmMain by getting {
            dependencies {
                implementation(compose.desktop.currentOs)
                implementation(compose.material3)
                implementation(compose.ui)
                implementation(compose.ui.graphics)
                implementation(libs.kotlinx.serialization.json)
                implementation(libs.kotlinx.coroutines.core)
            }
        }
    }
}

// Compose Desktop 应用打包
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

// 为了给 Windows 用户一个即开即用的 artifact，额外生成一个 fat-jar：
//   ./gradlew desktop:shadowJar  →  desktop/build/libs/desktop-*-all.jar
// 用户在 Windows 上 `java -jar desktop-*-all.jar` 即可。
// shadowJar 任务配置
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    archiveBaseName.set("unicorn-desktop-receiver")
    archiveClassifier.set("all")
    archiveVersion.set("0.1.0")
    // 指定入口（没有这个，java -jar 会报 no main manifest attribute）
    manifest {
        attributes["Main-Class"] = "com.unicorn.desktop.MainKt"
    }
    // 排除一些冲突的 META-INF
    mergeServiceFiles()
}
