plugins {
    java
}

group = "com.madscientiste.rcon"
version = "1.1.0"

repositories {
    mavenCentral()
}

java {
  toolchain {
    languageVersion = JavaLanguageVersion.of(25)
  }
}

// Note: Null-safety warnings are configured at IDE level via:
// - .settings/org.eclipse.jdt.core.prefs (Eclipse JDT)
// - .vscode/settings.json (VS Code Java extension)
// These warnings are from static analysis, not the Java compiler

dependencies {
    // Hytale Server API - compile only since it's provided at runtime
    compileOnly(files("../../libs/HytaleServer.jar"))

    // Testing
    testImplementation(libs.junit)
    testRuntimeOnly(files("../../libs/HytaleServer.jar"))
}

tasks.jar {
    // Set the archive name
    archiveBaseName.set("rcon")
}

// Task to generate password hash
tasks.register<JavaExec>("hashPassword") {
    group = "rcon"
    description = "Generate a password hash for RCON authentication"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("com.madscientiste.rcon.infrastructure.AuthenticationService")
    
    if (project.hasProperty("password")) {
        val password = project.property("password") as? String
        if (password != null) {
            args(password)
        } else {
            println("Error: password property is null")
        }
    } else {
        println("Usage: ./gradlew :rcon:hashPassword -Ppassword=your_password_here")
    }
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

