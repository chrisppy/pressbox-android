# SPDX-FileCopyrightText: 2026 Chris Palmer <chris@red-oxide.org>
# SPDX-License-Identifier: GPL-3.0-only

{ self, ... }:
{
  perSystem =
    { pkgs, ... }:
    {
      # Every file has an SPDX license and copyright (https://reuse.software).
      checks.reuse = pkgs.runCommand "pressbox-android-reuse-lint" { nativeBuildInputs = [ pkgs.reuse ]; } ''
        cd ${self}
        reuse lint
        touch $out
      '';
    };
}
