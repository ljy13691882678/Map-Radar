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

// Compose Desktop 应用入口声明
compose.desktop {
    application {
        mainClass = "com.unicorn.desktop.MainKt"
    }
}

// 自定义 fat-jar 任务：把所有 runtime 依赖 + 编译产物合并成一个 jar
// Windows 用户下载后 `java -jar unicorn-desktop-receiver-fat.jar` 直接启动
tasks.register<Jar>("fatJar") {
    group = "build"
    description = "Assembles a fat jar with all runtime dependencies (for java -jar)"

    archiveBaseName.set("unicorn-desktop-receiver")
    archiveClassifier.set("fat")
    archiveVersion.set("1.0.0")

    // 主类 manifest
    manifest {
        attributes["Main-Class"] = "com.unicorn.desktop.MainKt"
    }

    // 把 jvm 编译产物 + jvm 所有 runtime 依赖（包括 Compose Desktop 原生库）合并
    val jvmMain by kotlin.sourceSets
    from(jvmMain.output)
    from(kotlin.jvm().compilations["main"].runtimeDependencyFiles)

    // 处理元信息冲突 —— 同名 META-INF 文件保留第一个
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    // 排除一些已知不兼容的签名字段
    exclude("META-INF/*.SF", "META-INF/*.DSA", "META-INF/*.RSA")
}
