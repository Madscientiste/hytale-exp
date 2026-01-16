plugins {
    java
    alias(libs.plugins.spotless)
}

// Root project configuration
// The actual plugin code is in the 'playground' module

// Task to deploy all built plugins to mods/ directory
tasks.register("deploy") {
    group = "deployment"
    description = "Deploy all built plugin JARs to the mods/ directory"
    
    // Depend on all subproject builds
    dependsOn(subprojects.map { "${it.name}:build" })
    
    doLast {
        val modsDir = file(".server/mods")
        modsDir.mkdirs()
        
        // Find all JAR files in subproject build directories
        subprojects.forEach { project ->
            val buildLibsDir = project.layout.buildDirectory.dir("libs").get().asFile
            if (buildLibsDir.exists()) {
                buildLibsDir.listFiles { _, name -> name.endsWith(".jar") }?.forEach { jarFile ->
                    val targetFile = file("$modsDir/${jarFile.name}")
                    jarFile.copyTo(targetFile, overwrite = true)
                    println("✓ Deployed: ${jarFile.name} -> .server/mods/${jarFile.name}")
                }
            }
        }
    }
    
    // Disable configuration cache for this task (it uses file system operations)
    notCompatibleWithConfigurationCache("Uses file system operations")
}

// Optional: Add a build hook that runs deploy automatically
// You can run ./gradlew build deploy or just ./gradlew build (deploy separately)
tasks.register("buildAndDeploy") {
    group = "build"
    description = "Build all projects and deploy to mods/"
    dependsOn("build", "deploy")
}

subprojects {
    apply(plugin = "com.diffplug.spotless")
    
    configure<com.diffplug.gradle.spotless.SpotlessExtension> {
        java {
            target("**/*.java")
            googleJavaFormat("1.33.0")
            removeUnusedImports()
            trimTrailingWhitespace()
            endWithNewline()
        }
    }
}