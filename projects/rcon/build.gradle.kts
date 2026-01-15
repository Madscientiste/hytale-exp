plugins {
    java
}

group = "com.rcon"
version = "1.0.0"

repositories {
    mavenCentral()
}

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(25)
    }
}

dependencies {
    // Hytale Server API - compile only since it's provided at runtime
    compileOnly(files("../../libs/HytaleServer.jar"))

    // Testing
    testImplementation(libs.junit)
}

tasks.jar {
    // Set the archive name
    archiveBaseName.set("Rcon")
}

// Configure source sets to use app/src structure
sourceSets {
    main {
        java {
            setSrcDirs(listOf("app/src/main/java"))
        }
        resources {
            setSrcDirs(listOf("app/src/main/resources"))
        }
    }
    test {
        java {
            setSrcDirs(listOf("app/src/test/java"))
        }
    }
}

