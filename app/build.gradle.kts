import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/* Release signing: keystore.properties at the repo root (storeFile, storePassword, keyAlias,
 * keyPassword) names the release key, which lives outside the repo. Neither is ever committed
 * (.gitignore). Without the file a release build is left unsigned; debug builds always use the
 * machine's debug key, so a debug install and a release install cannot replace each other. */
val releaseKey = rootProject.file("keystore.properties").takeIf { it.isFile }?.let { file ->
    Properties().apply { file.inputStream().use { load(it) } }
}

/* The APK carries the notices it is distributed under: the repo's LICENSE and NOTICE.md are copied
 * into its assets at build time (one source of truth, shown under Settings → Open-source notices). */
val noticesDir = layout.buildDirectory.dir("generated/notices")
val copyNotices = tasks.register<Copy>("copyNotices") {
    from(rootProject.file("LICENSE"), rootProject.file("NOTICE.md"))
    into(noticesDir)
}

android {
    namespace = "io.github.aerithmetiq.alabasterdawnbox"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.aerithmetiq.alabasterdawnbox"
        minSdk = 26
        targetSdk = 36
        versionCode = 10000
        versionName = "1.0.0"
    }

    sourceSets.getByName("main").assets.srcDir(noticesDir.get().asFile)

    signingConfigs {
        if (releaseKey != null) {
            create("release") {
                storeFile = file(releaseKey.getProperty("storeFile"))
                storePassword = releaseKey.getProperty("storePassword")
                keyAlias = releaseKey.getProperty("keyAlias")
                keyPassword = releaseKey.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.findByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources {
            /* Bouncy Castle and the JavaSteam stack each carry these; none is read at runtime. */
            excludes += setOf(
                "META-INF/versions/9/OSGI-INF/MANIFEST.MF",
                "META-INF/INDEX.LIST",
                "META-INF/DEPENDENCIES",
            )
        }
    }
}

tasks.named("preBuild") { dependsOn(copyNotices) }
tasks.matching { it.name.startsWith("merge") && it.name.endsWith("Assets") }
    .configureEach { dependsOn(copyNotices) }

dependencies {
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("com.google.android.material:material:1.12.0")

    /* Steam: the client library, the full Bouncy Castle provider it needs on Android (the platform
     * ships a stripped one), and a QR encoder for the sign-in code. */
    implementation("in.dragonbra:javasteam:1.8.0")
    implementation("org.bouncycastle:bcprov-jdk18on:1.83")
    implementation("com.google.zxing:core:3.5.3")
    /* Compile-time only: JavaSteam's protobuf enums (a Steam Cloud file's state) extend a protobuf
     * type. JavaSteam already brings this version in at runtime. */
    compileOnly("com.google.protobuf:protobuf-java:4.31.1")
    /* Compile-time only: the download session gives JavaSteam an HTTP client sized for many chunk
     * requests at once (SteamSession). JavaSteam already brings this version in at runtime. */
    compileOnly("com.squareup.okhttp3:okhttp:5.3.2")

    /* The game download calls JavaSteam's coroutine API, and JavaSteam leaves the two decompressors its
     * depot chunks need (zstd, LZMA) for the app to supply. Versions are the ones JavaSteam 1.8.0 uses. */
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
    implementation("com.github.luben:zstd-jni:1.5.7-6@aar")
    implementation("org.tukaani:xz:1.11")

    /* The game host (from alabaster-android): serves the game to the WebView and injects its shim. */
    implementation("androidx.webkit:webkit:1.17.1")

    testImplementation("junit:junit:4.13.2")
    /* Unit tests only: a real org.json, because android.jar stubs it during local tests. */
    testImplementation("org.json:json:20250517")
}
