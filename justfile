# SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
# SPDX-License-Identifier: MPL-2.0

set dotenv-load := false

# List available recipes
default:
    @just --list

# Build a debug APK (app/build/outputs/apk/debug)
build:
    ./gradlew assembleDebug

# Build and install the debug app on a connected device or emulator
install:
    ./gradlew installDebug

# Build a release APK (unsigned unless signing is configured)
release:
    ./gradlew assembleRelease

# Run Android lint
lint:
    ./gradlew lint

# Check formatting and licensing
check:
    nix flake check

# Format Kotlin, Nix and the other configured files
fmt:
    nix fmt

# Check formatting without modifying files (the flake's treefmt check, run on a copy)
fmt-check:
    nix build --no-link ".#checks.$(nix eval --impure --raw --expr builtins.currentSystem).treefmt"

# Update all flake inputs
update:
    nix flake update

# Check formatting and the flake
verify: fmt-check check
