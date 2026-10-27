{
  description = "Desupon: Android client and its gomobile Go core";

  inputs.nixpkgs.url = "github:NixOS/nixpkgs/674a2028661e550a97400c1cc992b9cca9a668a1";

  outputs = { self, nixpkgs }:
    let
      system = "x86_64-linux";
      pkgs = import nixpkgs {
        inherit system;
        config = {
          allowUnfree = true;
          android_sdk.accept_license = true;
        };
      };
      lib = pkgs.lib;

      # FreeTurn v4.1.0 go.mod requires go 1.26.10.
      go = assert lib.versionAtLeast pkgs.go_1_26.version "1.26.10"; pkgs.go_1_26;
      buildGoModule = pkgs.buildGoModule.override { inherit go; };

      ndkVersion = "28.0.13004108";
      # AGP 9.1.1 default build-tools.
      buildToolsVersion = "36.0.0";
      android = pkgs.androidenv.composeAndroidPackages {
        # gomobile only accepts integer-named platform dirs (android-36); AGP compileSdk 37 uses android-37.0.
        platformVersions = [ "36" "37.0" ];
        buildToolsVersions = [ buildToolsVersion ];
        includeNDK = true;
        ndkVersions = [ ndkVersion ];
        includeCmake = false;
        includeEmulator = false;
        includeSystemImages = false;
        includeSources = false;
      };
      sdk = "${android.androidsdk}/libexec/android-sdk";

      gomobile = buildGoModule rec {
        pname = "sagernet-gomobile";
        version = "0.1.13";
        src = pkgs.fetchFromGitHub {
          owner = "sagernet";
          repo = "gomobile";
          tag = "v${version}";
          hash = "sha256-vJ/H066JOgKbgJIsh3y8o9zn1L9I30OSxJaei859Dl0=";
        };
        vendorHash = "sha256-/EMOJw54/i6fSfxTPheLarhDo7CK3YkSPENg8Wf7jtw=";
        subPackages = [ "cmd/gomobile" "cmd/gobind" ];
        ldflags = [ "-s" "-w" ];
        doCheck = false;
      };
    in {
      packages.${system} = {
        inherit gomobile;
        androidsdk = android.androidsdk;
      };

      devShells.${system}.default = pkgs.mkShellNoCC {
        packages = [
          go
          gomobile
          pkgs.jdk17
          pkgs.git
          android.androidsdk
        ];
        JAVA_HOME = pkgs.jdk17.home;
        ANDROID_HOME = sdk;
        ANDROID_SDK_ROOT = sdk;
        ANDROID_NDK_HOME = "${sdk}/ndk/${ndkVersion}";
        GOTOOLCHAIN = "local";
        GOFLAGS = "-buildvcs=false";
        # No host C compiler in the shell; gomobile sets CGO_ENABLED=1 for android targets itself.
        CGO_ENABLED = "0";
        # Maven's aapt2 is a dynamically linked ELF that does not run on NixOS.
        GRADLE_OPTS = "-Dorg.gradle.project.android.aapt2FromMavenOverride=${sdk}/build-tools/${buildToolsVersion}/aapt2";
      };
    };
}
