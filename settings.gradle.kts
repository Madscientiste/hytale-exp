plugins {
    // Apply the foojay-resolver plugin to allow automatic download of JDKs
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

rootProject.name = "hytale-mod-exp"
include("playground")

// All modules are in the projects/ directory (monorepo structure)
project(":playground").projectDir = file("projects/playground")
