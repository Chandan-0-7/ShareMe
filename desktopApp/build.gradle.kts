import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    kotlin("jvm")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}
kotlin { jvmToolchain(17) }
dependencies {
    implementation(project(":shared"))
    implementation(compose.desktop.currentOs)
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-swing:1.11.0")
}
compose.desktop {
    application {
        mainClass = "dev.shareme.desktop.MainKt"
        nativeDistributions {
            targetFormats(TargetFormat.Dmg, TargetFormat.Msi, TargetFormat.Deb)
            packageName = "ShareMe"
            packageVersion = "0.1.0"
            macOS { packageVersion = "1.0.0"; bundleID = "dev.shareme.desktop" }
            modules("java.naming", "jdk.crypto.ec")
        }
    }
}
