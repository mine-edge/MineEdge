## MineEdge - Mirror's Edge inspired movement for Minecraft.

This mod replaces vanilla movement with one that adds parkour mechanics into the game.  
MineEdge also provides first person camera movement and thirdperson player animations.

### Current features:
* Sliding
* Wall running
* Vaulting
* Mantling
* Pipe climbing
* Door pushing
  
Each of these features are togglable from the config file/menu

## Requirements

- Minecraft **1.21.1**
- NeoForge **21.1.250**
- Java **21**
- Cloth Config v15 API **15.0.140** (NeoForge, required on clients)

## Development

To develop, install JDK 21.
The Gradle wrapper provides the pinned Gradle version.

```sh
# Compile, check, and package the mod
./gradlew build

# Generate mod resources
./gradlew runData

# Proper server checks
./gradlew runGameTestServer

# Launch Minecraft with the mod loaded
./gradlew runClient

# Launch a dedicated development server
./gradlew runServer
```

Or, on NixOS, enter the supplied development environment with `devenv shell`.
It provides JDK 21, Gradle, and the native graphics/audio libraries needed by
the Minecraft client. The following shortcuts are available in the shell:

```sh
me-build   # Compile, check, and package the mod
me-client  # Launch Minecraft with the mod loaded
me-server  # Launch a dedicated development server
```

These commands work from any directory within the shell and forward extra
arguments to Gradle (for example, `me-build --offline`).

## License

MineEdge is licensed under the [MIT License](LICENSE).

Copyright (c) 2026 sysfab
