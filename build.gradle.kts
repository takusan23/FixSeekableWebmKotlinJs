plugins {
    kotlin("multiplatform") version "1.9.22"
}

group = "io.github.takusan23"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

kotlin {
    js {
        browser {
            // webpack とか
        }
        binaries.executable()
    }
}