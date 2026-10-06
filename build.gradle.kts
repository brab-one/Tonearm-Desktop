import org.jetbrains.compose.desktop.application.dsl.TargetFormat

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.compose.multiplatform)
}

group = "io.github.deadeyebarb.tonearm"
version = "1.7.0"

kotlin {
    jvmToolchain(21)
    // Code shared with the phone app (the tonearm submodule): Subsonic client, TLS, Lidarr/Brainarr,
    // Connect, YouTube Music.
    sourceSets["main"].kotlin.srcDir("tonearm/shared/src/main/kotlin")
}

dependencies {
    implementation(compose.desktop.currentOs)
    implementation(compose.material3)
    implementation(compose.materialIconsExtended)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.swing)
    implementation(libs.okhttp)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)
    implementation(libs.newpipe.extractor)
    implementation(libs.jna)
    implementation(libs.jna.platform)
    // Tags and cover art of local music files.
    implementation("net.jthink:jaudiotagger:3.0.1")

    testImplementation(kotlin("test"))
    testImplementation(libs.okhttp.mockwebserver)
}

// The Windows build's classpath, made on Linux: Skia's Windows runtime instead of this machine's.
val windowsRuntime by configurations.creating {
    extendsFrom(configurations.runtimeClasspath.get())
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-x64")
    exclude(group = "org.jetbrains.skiko", module = "skiko-awt-runtime-linux-arm64")
}
dependencies { windowsRuntime(compose.desktop.windows_x64) }

/**
 * The app's jars for the Windows portable build (packaging/build-windows.sh adds the runtime and libmpv).
 * Jars are named with their group: JetBrains' and AndroidX's Compose runtimes share file names.
 */
val windowsJars by tasks.registering {
    dependsOn(tasks.jar)
    val out = layout.buildDirectory.dir("windows/app")
    outputs.dir(out)
    doLast {
        val dir = out.get().asFile
        dir.deleteRecursively()
        dir.mkdirs()
        tasks.jar.get().archiveFile.get().asFile.copyTo(dir.resolve("tonearm-desktop.jar"))
        for (artifact in windowsRuntime.resolvedConfiguration.resolvedArtifacts) {
            artifact.file.copyTo(dir.resolve("${artifact.moduleVersion.id.group}.${artifact.file.name}"), overwrite = true)
        }
    }
}

compose.desktop {
    application {
        mainClass = "io.github.deadeyebarb.tonearm.desktop.MainKt"
        // Extra files shipped with the app (libmpv on Windows); found at runtime via compose.application.resources.dir.
        nativeDistributions.appResourcesRootDir.set(project.layout.projectDirectory.dir("packaging/resources"))
        // NewPipe's JavaScript engine (Rhino), JNA and serialization rely on reflection; size isn't worth the risk.
        buildTypes.release.proguard { isEnabled.set(false) }
        nativeDistributions {
            targetFormats(TargetFormat.Deb, TargetFormat.Rpm, TargetFormat.Msi)
            packageName = "Tonearm"
            packageVersion = "1.7.0"
            description = "Lossless Subsonic player with Lidarr and Brainarr"
            vendor = "Deadeyebarb"
            modules("java.naming", "java.net.http", "jdk.httpserver", "jdk.crypto.ec", "jdk.unsupported", "java.sql", "java.management", "java.logging")
            linux {
                iconFile.set(project.file("src/main/resources/icon.png"))
                menuGroup = "AudioVideo"
                appCategory = "Audio"
            }
            windows {
                iconFile.set(project.file("packaging/icon.ico"))
                menu = true
                shortcut = true
                dirChooser = true
                upgradeUuid = "8f0b0c1e-5c0a-4f7e-9a52-6a3f3b1f2d10"
            }
        }
    }
}
