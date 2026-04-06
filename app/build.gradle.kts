import org.gradle.api.tasks.compile.JavaCompile
import java.nio.ByteBuffer
import java.nio.charset.CharacterCodingException
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("org.jetbrains.kotlin.kapt")
}

android {
    namespace = "com.palmclaw"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.palmclaw"
        minSdk = 24
        targetSdk = 35
        versionCode = 6
        versionName = "0.1.5"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE.md"
            excludes += "/META-INF/NOTICE.md"
        }
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

val suspiciousEncodingMarkers = listOf("锟", "烫", "閿", "鈧", "闁", "閸", "濞", "鍙", "�")
val textEncodingIncludes = listOf(
    "**/*.kt",
    "**/*.kts",
    "**/*.xml",
    "**/*.md",
    "**/*.json",
    "**/*.txt",
    "**/*.properties"
)

val verifyTextEncoding by tasks.registering {
    group = "verification"
    description = "Fails if app text sources are not valid UTF-8 or contain common mojibake markers."

    val sourceFiles = fileTree("src/main") {
        textEncodingIncludes.forEach(::include)
    }
    inputs.files(sourceFiles)

    doLast {
        val decoder = StandardCharsets.UTF_8
            .newDecoder()
            .onMalformedInput(CodingErrorAction.REPORT)
            .onUnmappableCharacter(CodingErrorAction.REPORT)
        val failures = mutableListOf<String>()

        sourceFiles.files
            .sortedBy { it.relativeTo(projectDir).invariantSeparatorsPath }
            .forEach { file ->
                val text = try {
                    decoder.reset()
                    decoder.decode(ByteBuffer.wrap(file.readBytes())).toString()
                } catch (_: CharacterCodingException) {
                    failures += "${file.relativeTo(projectDir)} is not valid UTF-8."
                    return@forEach
                }

                if (!file.name.endsWith(".md", ignoreCase = true)) {
                    suspiciousEncodingMarkers.firstOrNull(text::contains)?.let { marker ->
                        failures += "${file.relativeTo(projectDir)} contains suspicious mojibake marker '$marker'."
                    }
                }
            }

        if (failures.isNotEmpty()) {
            throw GradleException(
                buildString {
                    appendLine("Text encoding verification failed.")
                    failures.forEach(::appendLine)
                }
            )
        }
    }
}

tasks.named("preBuild").configure {
    dependsOn(verifyTextEncoding)
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.10.0")
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")

    implementation(composeBom)
    androidTestImplementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("com.google.android.material:material:1.12.0")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    kapt("androidx.room:room-compiler:2.8.4")

    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:okhttp-sse:4.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.3")
    implementation("io.noties.markwon:core:4.6.2")
    implementation("io.noties.markwon:ext-tables:4.6.2")
    implementation("androidx.media3:media3-exoplayer:1.9.2")
    implementation("androidx.media3:media3-ui:1.9.2")
    implementation("androidx.work:work-runtime-ktx:2.11.1")
    implementation("com.larksuite.oapi:oapi-sdk:2.5.3")
    implementation("com.sun.mail:android-mail:1.6.7")
    implementation("com.sun.mail:android-activation:1.6.7")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.5")
    testImplementation("junit:junit:4.13.2")
}
