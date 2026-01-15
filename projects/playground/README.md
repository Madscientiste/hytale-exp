# Example Hytale Plugin

This is an example Hytale server plugin demonstrating the basic structure and setup.

## Project Structure

```
playground/
├── app/
│   ├── src/
│   │   ├── main/
│   │   │   ├── java/com.playground/
│   │   │   │   └── Playground.java
│   │   │   └── resources/
│   │   │       └── plugin.json
│   │   └── test/
│   ├── build.gradle.kts
├── gradle/
├── build.gradle.kts
├── settings.gradle.kts
└── gradlew / gradlew.bat
```

## Building

```bash
# From the project root directory
./gradlew build

# The JAR will be in playground/build/libs/Playground-1.0.0.jar
```

## Installation

1. Build the plugin with `./gradlew build` (from project root)
2. Copy `playground/build/libs/Playground-1.0.0.jar` to your Hytale server's `mods/` folder
3. Start the server

## Plugin Structure

### plugin.json

The plugin manifest file defines metadata about your plugin:

```json
{
  "Group": "com.playground",
  "Name": "Playground",
  "Version": "1.0.0",
  "Description": "An example Hytale server plugin",
  "Main": "com.playground.Playground"
}
```

### Main Plugin Class

Your plugin class extends `JavaPlugin` and implements lifecycle methods:

- `setup()` - Called during server setup, register configs here
- `start()` - Called when plugin starts, register commands/events here
- `shutdown()` - Called when plugin stops, cleanup resources here

## Requirements

- Java 25 or later
- Hytale Server (HytaleServer.jar must be in the `libs/` directory at the project root)
