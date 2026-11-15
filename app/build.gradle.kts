import java.awt.Color
import java.awt.image.BufferedImage
import javax.imageio.ImageIO

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("app.cash.paparazzi")
}

android {
    namespace = "dev.androidproxy"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.sangjun.androidproxy"
        minSdk = 33
        targetSdk = 36
        versionCode = 2
        versionName = "0.2.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        compose = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.all { it.testLogging { events("passed", "failed", "skipped") } }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.10.2")
    testImplementation("junit:junit:4.13.2")
}

// Play 스토어 휴대전화 스크린샷: StoreScreenshotTest 를 기록하고 store/screenshot-<번호>.png 로 옮긴다.
// Play 는 알파 없는 PNG 를 받으므로 RGB 로 바꿔 쓴다.
tasks.register("storeScreenshots") {
    dependsOn("recordPaparazziDebug")
    val images = layout.projectDirectory.dir("src/test/snapshots/images")
    val store = rootProject.layout.projectDirectory.dir("store")
    doLast {
        val name = Regex("""dev\.androidproxy\.ui_StoreScreenshotTest_screenshot(\d+).*\.png""")
        images.asFile.listFiles().orEmpty().sortedBy { it.name }.forEach { file ->
            val number = name.matchEntire(file.name)?.groupValues?.get(1) ?: return@forEach
            val source = ImageIO.read(file)
            check(source.width == 1080 && source.height == 1920) { "${file.name}: ${source.width}x${source.height}" }
            val rgb = BufferedImage(source.width, source.height, BufferedImage.TYPE_INT_RGB)
            rgb.createGraphics().apply {
                color = Color.WHITE
                fillRect(0, 0, source.width, source.height)
                drawImage(source, 0, 0, null)
                dispose()
            }
            val out = store.file("screenshot-$number.png").asFile
            ImageIO.write(rgb, "png", out)
            println("${out.name}: ${rgb.width}x${rgb.height} RGB")
        }
    }
}
