{
  description = "unicoach development environment";

  inputs = {
    nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
    flake-utils.url = "github:numtide/flake-utils";
  };

  outputs = { self, nixpkgs, flake-utils }:
    flake-utils.lib.eachDefaultSystem (system:
      let
        pkgs = nixpkgs.legacyPackages.${system};
      in {
        devShells.default = pkgs.mkShell {
          packages = [
            # JVM — replaces eclipse-temurin:21-jdk Docker image.
            # Temurin is the recommended OpenJDK distribution for production workloads.
            pkgs.temurin-bin-21

            # PostgreSQL 18 — replaces postgres:18 Docker image.
            # Includes psql, pg_isready, initdb, and postgres server binaries.
            pkgs.postgresql_18

            # Python 3 — required to run schemathesis (replaces schemathesis:stable image).
            pkgs.python3

            # Deno — replaces node:20-alpine + npx prettier for Markdown formatting.
            pkgs.deno

            # ktlint — the repo's only Kotlin linter/formatter. bin/format wraps
            # it (format and, with -c, check); there is no Gradle ktlint plugin,
            # so this is the single ktlint and there is no second engine to keep
            # in version-sync.
            pkgs.ktlint

            # git — nix sets GIT_EXEC_PATH to its own nix store path, which
            # makes the macOS /usr/bin/git stub unable to find subcommands.
            # Including git here puts a fully self-consistent git on PATH.
            pkgs.git

            # OpenTofu — the open-source, drop-in Terraform engine used to apply
            # the AWS infrastructure under infra/. Chosen over HashiCorp
            # Terraform because the latter's BSL license would force allowUnfree.
            pkgs.opentofu

            # AWS CLI v2 — used by bin/deploy (S3 upload, SSM Run Command) and
            # for out-of-band secret seeding into SSM Parameter Store.
            pkgs.awscli2
          ];

          # Diagnostic banner goes to stderr, not stdout, so that
          # `$(nix develop -c <script>)` captures the script's stdout cleanly
          # (e.g. the rfc-pipeline scripts print a SHA / run state to stdout).
          shellHook = ''
            {
              echo ""
              echo "🛠  unicoach dev environment active"
              echo "   java:     $(java -version 2>&1 | head -1)"
              echo "   postgres: $(psql --version)"
              echo "   python:   $(python3 --version)"
              echo "   deno:     $(deno --version | head -1)"
              echo "   ktlint:   $(ktlint --version)"
              echo ""
            } >&2
          '';
        };
      });
}
