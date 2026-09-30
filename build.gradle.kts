import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.4.20"
    kotlin("plugin.serialization") version "2.4.20"
    application
}

group = "dev.c4editor"
version = "0.1.0"

repositories {
    mavenCentral()
}

val javafxVersion = "26.0.2"

// JavaFX публикует нативные артефакты под каждую платформу — выбираем текущую.
val javafxPlatform: String = run {
    val os = System.getProperty("os.name").lowercase()
    val arch = System.getProperty("os.arch").lowercase()
    val arm = arch == "aarch64" || arch == "arm64"
    when {
        os.contains("mac") -> if (arm) "mac-aarch64" else "mac"
        os.contains("win") -> "win"
        else -> if (arm) "linux-aarch64" else "linux"
    }
}

dependencies {
    listOf("base", "graphics", "controls").forEach { module ->
        implementation("org.openjfx:javafx-$module:$javafxVersion:$javafxPlatform")
    }
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")
    // Отрисовка диаграмм кода. Вариант библиотеки под лицензией MIT.
    implementation("net.sourceforge.plantuml:plantuml-mit:1.2026.8")

    testImplementation(kotlin("test"))
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_25)
    }
}

application {
    mainClass.set("dev.c4editor.MainKt")
    applicationDefaultJvmArgs = listOf("--enable-native-access=ALL-UNNAMED")
}

tasks.test {
    useJUnitPlatform()
    systemProperty("java.awt.headless", "true")
}
