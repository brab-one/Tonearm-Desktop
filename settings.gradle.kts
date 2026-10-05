pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositories {
        mavenCentral()
        google()
        // NewPipeExtractor (YouTube Music) and its nanojson fork are only published on JitPack.
        maven("https://jitpack.io") {
            content { includeGroupByRegex("com\\.github\\.(?i)teamnewpipe") }
        }
    }
    // One set of versions for the phone and the desktop app: the phone repository's catalog (submodule).
    versionCatalogs {
        create("libs") { from(files("tonearm/gradle/libs.versions.toml")) }
    }
}

rootProject.name = "tonearm-desktop"
