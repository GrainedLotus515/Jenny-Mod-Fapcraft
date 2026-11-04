{
  description = "Fapcraft dev shell: JDK 21 for the NeoForge 1.21.1 port (neoforge/) and the 1.12.2 Maven build";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";

  outputs = { self, nixpkgs }:
    let
      systems = [ "x86_64-linux" "aarch64-linux" ];
      forAllSystems = f: nixpkgs.lib.genAttrs systems (system: f nixpkgs.legacyPackages.${system});
    in
    {
      devShells = forAllSystems (pkgs:
        let
          jdk = pkgs.jdk21;
          # Native libraries LWJGL loads when `./gradlew runClient` starts the game.
          runtimeLibs = with pkgs; [
            libGL
            glfw
            openal
            libpulseaudio
            alsa-lib
            udev
            flite
            wayland
            libxkbcommon
            libx11
            libxcursor
            libxext
            libxrandr
            libxxf86vm
            libxi
          ];
        in
        {
          default = pkgs.mkShell {
            packages = [ jdk pkgs.maven pkgs.git ];
            JAVA_HOME = jdk.home;
            LD_LIBRARY_PATH = pkgs.lib.makeLibraryPath runtimeLibs;
          };
        });
    };
}
