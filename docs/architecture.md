# Hytale Mod Development - Architecture Documentation

## Overview

This project is a **monorepo** for developing Hytale server plugins/modifications. It uses Gradle as the build system and follows a modular architecture where each plugin is a separate module within the `projects/` directory.

## Project Structure

```
hytale-mod-exp/
├── build.gradle.kts              # Root build configuration
├── settings.gradle.kts           # Gradle project structure definition
├── gradle.properties             # Gradle configuration
├── gradlew / gradlew.bat         # Gradle wrapper scripts
├── Makefile                      # Convenience commands for common tasks
│
├── gradle/                       # Gradle wrapper and version catalog
│   ├── libs.versions.toml        # Dependency version catalog
│   └── wrapper/                  # Gradle wrapper files
│
├── libs/                         # External dependencies
│   ├── HytaleServer.jar          # Hytale server runtime (from official portal)
│   ├── Assets.zip                # Hytale assets (from official portal)
│   └── HytaleServer.aot          # AOT compiled server
│
├── projects/                     # Plugin modules (monorepo structure)
│   ├── <module-name>/            # Example plugin module
│   │   ...
│   └── rcon/                     # Another plugin module
│       ├── build.gradle.kts
│       ├── docs/                 # Module-specific documentation
│       └── app/
│           └── ...
│
├── .server/                      # Server runtime directory
│   ├── run.sh                    # Server startup script
│   ├── mods/                     # Deployed plugin JARs (auto-populated)
│   └── universe/                 # Server universe/worlds
│
├── .vscode/                      # VS Code workspace configuration
│   ├── settings.json             # IDE settings
│   ├── tasks.json                # Build tasks
│   ├── launch.json               # Debug configurations
│   └── extensions.json           # Recommended extensions
│
└── docs/                         # Documentation
    └── architecture.md           # This file
```

## Monorepo Architecture

### Concept

All plugin modules live under `projects/`, similar to JavaScript monorepos (npm workspaces, yarn workspaces, pnpm). This provides:

- **Centralized build configuration**: Shared Gradle setup
- **Easy module management**: Add new plugins by creating directories
- **Unified deployment**: All plugins deploy to the same `mods/` directory
- **Shared dependencies**: Common libraries defined once

### Module Structure

Each module in `projects/` follows this structure:

```
projects/<module-name>/
├── build.gradle.kts              # Module-specific build config
├── docs/                         # Module-specific documentation (optional)
│   └── README.md                 # Module documentation
└── app/
    └── src/
        ├── main/
        │   ├── java/             # Java source code
        │   └── resources/
        │       └── manifest.json # Plugin metadata
        └── test/                 # Test source code
```

**Note**: Each module can have its own `docs/` directory for module-specific documentation (e.g., `projects/rcon/docs/`). This is useful for documenting complex plugins with their own setup instructions, API documentation, or usage examples.

### Adding a New Module

1. Create directory: `projects/my-plugin/`
2. Add to `settings.gradle.kts`:
   ```kotlin
   include("my-plugin")
   project(":my-plugin").projectDir = file("projects/my-plugin")
   ```
3. Create `projects/my-plugin/build.gradle.kts` (copy from existing module)
4. Create source structure: `projects/my-plugin/app/src/main/java/...`

## Build System

### Gradle Configuration

- **Build Tool**: Gradle 9.2.1 (via wrapper)
- **Build Language**: Kotlin DSL (`.gradle.kts`)
- **Java Version**: Java 25 (configured via toolchain)
- **Configuration Cache**: Enabled for faster builds

### Key Build Files

#### `settings.gradle.kts`
Defines the project structure and modules:
```kotlin
rootProject.name = "hytale-mod-exp"
include("playground")
include("rcon")

// All modules in projects/ directory
project(":playground").projectDir = file("projects/playground")
project(":rcon").projectDir = file("projects/rcon")
```

#### `build.gradle.kts` (Root)
Contains shared configuration and deployment tasks:
- `deploy` task: Copies all built JARs to `.server/mods/`
- `buildAndDeploy` task: Builds and deploys in one step

#### `projects/<module>/build.gradle.kts`
Module-specific configuration:
- Dependencies (HytaleServer.jar as `compileOnly`)
- Source set configuration
- JAR naming

### Build Tasks

```bash
# Build all modules
./gradlew build

# Build specific module
./gradlew :playground:build

# Run tests
./gradlew test

# Deploy plugins
./gradlew deploy

# Build and deploy
./gradlew buildAndDeploy
```

## Module Configuration

### Source Sets

Modules use a custom source set structure where source code is in `app/src/`:

```kotlin
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
```

### Dependencies

Each module depends on:
- **HytaleServer.jar**: Provided at runtime, marked as `compileOnly`
- **JUnit**: For testing (via version catalog)

```kotlin
dependencies {
    compileOnly(files("../../libs/HytaleServer.jar"))
    testImplementation(libs.junit)
}
```

### Plugin Manifest

Each plugin requires `manifest.json` in `app/src/main/resources/`:

```json
{
  "Group": "com.playground",
  "Name": "Playground",
  "Version": "1.0.0",
  "Description": "An example Hytale server plugin",
  "Main": "com.playground.Playground",
  "ServerVersion": "*",
  "Dependencies": {},
  "OptionalDependencies": {}
}
```

## Server Runtime

### Server Directory (`.server/`)

The `.server/` directory contains all server runtime files:

- **`mods/`**: Auto-populated by Gradle deploy task with built plugin JARs
- **`universe/`**: Server worlds/universe data
- **`run.sh`**: Server startup script

### Server Startup Script

The `run.sh` script handles:

1. **Path Resolution**: Finds HytaleServer.jar and Assets.zip
2. **Auto-detection**: Automatically detects `mods/` directory
3. **Asset Loading**: Passes `--assets libs/Assets.zip` if available
4. **Configuration**: Sets auth mode, bind address, transport

**Key Features**:
- Auto-detects `mods/` directory (no need for `--mods` flag)
- Conditionally adds `--assets` if Assets.zip exists
- Supports `--bare` mode for plugin testing
- Configurable via environment variables or CLI args

### Server Modes

#### Full Mode (Default)
```bash
make run
# or
.server/run.sh
```

Runs full server with:
- World loading
- Port binding
- Asset loading
- All server features

#### Bare Mode
```bash
make run-bare
# or
.server/run.sh --bare
```

Runs minimal server for plugin testing:
- No world loading
- No port binding
- No directory creation
- **Plugins still load** (for testing)

## Development Workflow

### 1. Build and Deploy

```bash
# Build all modules and deploy to .server/mods/
make build

# Or step by step
make build-only  # Just build
make deploy      # Deploy built JARs
```

### 2. Test

```bash
# Run all tests
make test

# Run specific module tests
./gradlew :playground:test
```

### 3. Run Server

```bash
# Full server (requires assets)
make run

# Bare mode (plugin testing)
make run-bare
```

### 4. Development Cycle

1. Edit plugin code in `projects/<module>/app/src/main/java/`
2. Build: `make build`
3. Test: `make test`
4. Run: `make run-bare` (for quick testing) or `make run` (full server)

## Deployment Process

### Automatic Deployment

The `deploy` Gradle task automatically:

1. Finds all built JARs in `projects/*/build/libs/*.jar`
2. Copies them to `.server/mods/`
3. Overwrites existing JARs (for updates)

### Manual Deployment

```bash
# Deploy specific module
./gradlew :playground:build
cp projects/playground/build/libs/Playground-1.0.0.jar .server/mods/
```

## IDE Configuration

### VS Code

The `.vscode/` directory contains:

- **`settings.json`**: Java/Gradle configuration, file associations
- **`tasks.json`**: Pre-configured Gradle tasks
- **`launch.json`**: Debug configurations
- **`extensions.json`**: Recommended extensions (Java, Gradle, Kotlin)

### Recommended Extensions

- `vscjava.vscode-java-pack`: Java language support
- `vscjava.vscode-gradle`: Gradle integration
- `fwcd.kotlin`: Kotlin support (for `.gradle.kts` files)
- `usernamehw.errorlens`: Inline error highlighting

## Dependencies

### External Dependencies

Located in `libs/` (not committed to git):

- **HytaleServer.jar**: Hytale server runtime (download from official portal)
- **Assets.zip**: Hytale game assets (download from official portal)

### Build Dependencies

Managed via `gradle/libs.versions.toml`:

- **JUnit 4.13.2**: Testing framework

## File Organization

### Git Ignore Strategy

- **Build artifacts**: `build/`, `.gradle/`, `**/build/`
- **IDE files**: `.idea/`, `*.iml` (but keep `.vscode/`)
- **Server runtime**: `.server/mods/*.jar`, `.server/universe/`
- **External libs**: `libs/HytaleServer.*`, `libs/Assets.*`

### Committed Files

- Source code
- Build configuration files
- VS Code workspace settings
- Documentation
- Gradle wrapper files

## Build Output

### Module Build Output

Each module produces:
- **JAR**: `projects/<module>/build/libs/<ModuleName>-<version>.jar`
- **Test Reports**: `projects/<module>/build/reports/tests/`
- **Classes**: `projects/<module>/build/classes/`

### Deployment Output

All plugin JARs are copied to:
- **`.server/mods/<ModuleName>-<version>.jar`**

## Command Reference

### Make Commands

```bash
make help        # Show all available commands
make build       # Build and deploy all plugins
make build-only  # Build without deploying
make test        # Run all tests
make deploy      # Deploy built plugins
make run         # Run full server
make run-bare    # Run server in bare mode
make clean       # Clean build artifacts
```

### Gradle Commands

```bash
./gradlew build              # Build all modules
./gradlew :playground:build  # Build specific module
./gradlew test               # Run all tests
./gradlew deploy             # Deploy plugins
./gradlew buildAndDeploy     # Build and deploy
./gradlew clean              # Clean build artifacts
./gradlew tasks              # List all tasks
```

## Plugin Lifecycle

Hytale plugins implement the `JavaPlugin` interface with three lifecycle methods:

1. **`setup()`**: Called during server setup phase
   - Register configs
   - Prepare resources
   - Initialize data structures

2. **`start()`**: Called when plugin starts
   - Register commands
   - Register event handlers
   - Start background tasks

3. **`shutdown()`**: Called when plugin stops
   - Clean up resources
   - Save data
   - Stop background tasks

## Troubleshooting

### Server Shuts Down Immediately

**Problem**: Server fails to start due to missing assets

**Solution**: 
- Use `make run-bare` for plugin testing
- Or download Assets.zip from official portal and place in `libs/`

### Plugin Not Loading

**Problem**: Plugin JAR not found in mods directory

**Solution**:
- Run `make deploy` to copy JARs to `.server/mods/`
- Check `manifest.json` has correct `Main` class path

### Build Failures

**Problem**: Cannot find HytaleServer.jar

**Solution**:
- Ensure `libs/HytaleServer.jar` exists
- Download from official portal if missing
- Check path in `build.gradle.kts` is correct (`../../libs/HytaleServer.jar`)

## Best Practices

1. **Module Naming**: Use lowercase, hyphenated names (e.g., `my-plugin`)
2. **Package Structure**: Follow Java conventions (`com.yourname.pluginname`)
3. **Version Management**: Update version in both `build.gradle.kts` and `manifest.json`
4. **Testing**: Write tests in `app/src/test/java/`
5. **Documentation**: 
   - Document complex plugins in module-specific `docs/` directory (e.g., `projects/rcon/docs/`)
   - Include README.md for setup, configuration, and usage instructions
   - Keep architecture-level docs in root `docs/` directory
6. **Git Workflow**: Commit source code, ignore build artifacts and server runtime

## Future Enhancements

Potential improvements:

- [ ] Shared library module for common utilities
- [ ] Integration test framework
- [ ] Plugin dependency management between modules
- [ ] Automated version bumping
- [ ] CI/CD pipeline configuration
- [ ] Docker containerization for server

