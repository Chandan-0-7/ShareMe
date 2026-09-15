import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("multiplatform")
    id("com.android.kotlin.multiplatform.library")
    id("org.jetbrains.compose")
    id("org.jetbrains.kotlin.plugin.compose")
}

kotlin {
    android {
        namespace = "dev.shareme.shared"
        compileSdk = 36
        minSdk = 26
        withHostTest {}
        compilerOptions { jvmTarget.set(JvmTarget.JVM_17) }
    }
    jvm("desktop") { compilerOptions { jvmTarget.set(JvmTarget.JVM_17) } }
    iosArm64 { binaries.framework { baseName = "ShareMe"; isStatic = true } }
    iosSimulatorArm64 { binaries.framework { baseName = "ShareMe"; isStatic = true } }
    applyDefaultHierarchyTemplate()
    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material)
            implementation(compose.ui)
            api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
        }
        commonTest.dependencies { implementation(kotlin("test")) }
        val jvmSharedMain by creating {
            dependsOn(commonMain.get())
            dependencies {
                implementation("org.bouncycastle:bcpkix-jdk18on:1.79")
                implementation("com.google.zxing:core:3.5.3")
            }
        }
        androidMain.get().dependsOn(jvmSharedMain)
        val desktopMain by getting { dependsOn(jvmSharedMain) }
        val desktopTest by getting { dependencies { implementation(kotlin("test-junit")) } }
    }
}
