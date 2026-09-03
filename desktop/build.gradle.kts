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

// Compose Desktop 应用入口声明。
// 注意：原生打包 (nativeDistributions / targetFormats) 要求版本 MAJOR > 0，
// 而且 CI 里 ubuntu-latest 打不了 .exe/.msi/.dmg —— 所以我们不配置原生打包，
// 只走 shadowJar fat-jar 给 Windows 用户用 java -jar 启动。
compose.desktop {
    application {
        mainClass = "com.unicorn.desktop.MainKt"
    }
}

// fat-jar —— 目标：Windows 用户下载后 `java -jar unicorn-desktop-receiver-all.jar` 直接启动
tasks.withType<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>().configureEach {
    archiveBaseName.set("unicorn-desktop-receiver")
    archiveClassifier.set("all")
    archiveVersion.set("1.0.0")
    manifest {
        attributes["Main-Class"] = "com.unicorn.desktop.MainKt"
    }
    mergeServiceFiles()
}
