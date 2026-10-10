{ pkgs, lib, config, ... }:

let
    minecraftLibraries = with pkgs; [
        stdenv.cc.cc.lib
        libGL
        glfw3
        openal
        alsa-lib
        libpulseaudio
        udev
        libx11
        libxcursor
        libxext
        libxi
        libxrandr
        libxinerama
        libxxf86vm
        wayland
        libxkbcommon
    ];
in
{
    packages = minecraftLibraries;

    # LWJGL loads these libraries at runtime; use the host's NixOS GPU driver.
    env.LD_LIBRARY_PATH = "${lib.makeLibraryPath minecraftLibraries}:/run/opengl-driver/lib";

    languages.java = {
        enable = true;
        jdk.package = pkgs.jdk21;

        gradle.enable = true;
        lsp.enable = true;
    };

    scripts = {
        me-server = {
            description = "Launch the Minecraft development server";
            exec = ''
                cd ${lib.escapeShellArg config.devenv.root}
                exec ./gradlew runServer "$@"
            '';
        };

        me-client = {
            description = "Launch the Minecraft development client";
            exec = ''
                cd ${lib.escapeShellArg config.devenv.root} || exit 1
                rm -rf -- run/logs || exit 1
                exec ./gradlew runClient "$@"
            '';
        };

        me-build = {
            description = "Compile, check, and package MineEdge";
            exec = ''
                cd ${lib.escapeShellArg config.devenv.root}
                exec ./gradlew build "$@"
            '';
        };
    };
}
