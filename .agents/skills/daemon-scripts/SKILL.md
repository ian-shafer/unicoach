---
name: daemon-scripts
description:
  Architectural standards and principles for Unix executable lifecycle scripts
  (start, stop, check, restart).
---

# 🤖 Skill: Daemon Script Standardization

This skill enforces DRY principles, reliable Unix daemon polling semantics, and
structural safety when generating or modifying shell scripts managing
long-running application binaries or Docker containers.

## 🏗 Core Architecture

When implementing execution scripts for a service, NEVER build custom standalone
scripts full of execution loops. ALWAYS extract complex logic into reusable
generic engine scripts (e.g., `daemon-start`, `daemon-stop`, `wait-for`). The
exposed application scripts (e.g., `rest-server-start`) should function
exclusively as **Thin Wrappers**.

### Example Thin Wrapper (`bin/rest-server-start`)

```bash
#!/bin/bash
set -e
source "$(dirname "$0")/common"
bin/daemon-start -p8080 "rest-server"
```

## 📜 Mandatory Rules

1. **DRY Abstractions First**:
   - Extract conditional wait-loops into isolated tools (e.g., `wait-for`).
   - Extract atomic evaluations into specific components (e.g., `check-pid`,
     `check-port`).
   - Never duplicate standard bash `while` polling logic.
2. **Side-Effect Isolation**:
   - Do NOT place execution side-effects (like `mkdir -p var/run` or creating
     cache files) inside passive shared dependencies (like `bin/common`).
   - Scope side-effects into the target activation hook itself (like
     `daemon-start`).
3. **Explicit PID and Port Mapping**:
   - Persist operational states down to physical tracking files
     (`var/run/[service].pid` and `var/run/[service].port`).
4. **Structural File Integrity**:
   - Verify the structural existence of cache files (`if [ ! -f "$FILE" ];`)
     before attempting to parse them via `cat` to prevent bash syntax errors.
5. **The MacOS Docker Edge Case**:
   - Docker Desktop on MacOS runs inside a hidden VM. Running `kill -0` on the
     raw container Linux PID fails on the host Mac bash.
   - **Solution**: Background the host execution string (e.g.,
     `docker compose up ... &`) and trap its bash shell ID via `$!` to use as
     the persistent mapping PID. The Docker Compose framework routes termination
     signals down through the VM layer for you.
6. **Evaluate Exit Codes, Not Text**:
   - Always evaluate robust Unix exit status codes (`$?`) to determine success
     or failure states rather than utilizing `grep` or parsing raw standard
     output.
7. **Physical Sandbox Idempotency**:
   - Tests should be able to be run concurrently with the local development
     daemons. There should be no shared state between test daemons and local
     development daemons.
8. **Active Health Polling**:
   - Never use arbitrary `sleep` timeouts to wait for service readiness, unless
     it is absolutely necessary.
   - Employ active container or socket probing logic. For databases, repeatedly
     poll the connection directly (e.g., `psql -c '\q'`) until it resolves or
     times out.
