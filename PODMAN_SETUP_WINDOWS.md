# Podman Setup on Windows — Step by Step

This guide covers installing Podman on Windows using both the Desktop GUI
and the CLI, then running the task-orchestrator locally.

---

## Prerequisites

- **Windows 11** (or Windows 10 version 2004+)
- **Administrator access** (needed to enable WSL 2)
- **Java 21+** installed

---

## Part 1 — Install WSL 2

Podman runs Linux containers inside a lightweight VM. On Windows, it uses
WSL 2 (Windows Subsystem for Linux) as the backend.

Open **PowerShell as Administrator** and run:

```powershell
wsl --install
```

This installs WSL 2 with the default Ubuntu distribution.
**Restart your computer** when prompted.

After restart, verify WSL is working:

```powershell
wsl --version
```

You should see `WSL version: 2.x.x`.

---

## Part 2 — Install Podman

### Option A: Podman Desktop (GUI + CLI)

This is the easiest method — installs both the desktop app and the `podman` CLI.

1. Download from https://podman-desktop.io/downloads
2. Run the `.exe` installer
3. Follow the wizard — accept defaults
4. When it finishes, open **Podman Desktop**
5. It will prompt you to create a Podman machine — click **Create** and **Start**

That's it. The CLI `podman` is now on your PATH.

### Option B: Podman CLI only (no GUI)

If you don't want the desktop app:

**Using Winget:**

```powershell
winget install RedHat.Podman
```

**Using Chocolatey:**

```powershell
choco install podman-cli
```

**Using MSI installer:**

Download from https://github.com/containers/podman/releases
→ pick `podman-X.X.X-setup.exe`

---

## Part 3 — Initialize the Podman Machine

Open a **regular PowerShell** (not admin) and run:

```powershell
# Create the VM with enough resources for Kafka + PostgreSQL
podman machine init --cpus 4 --memory 4096 --disk-size 60
```

This downloads a Fedora-based VM image (~800MB). Takes 1-2 minutes.

Then start it:

```powershell
podman machine start
```

Takes a few seconds. Verify it's running:

```powershell
podman machine list
```

You should see:

```
NAME                    VM TYPE     CREATED         LAST UP         CPUS    MEMORY      DISK SIZE
podman-machine-default  wsl         2 minutes ago   Currently running  4    4.096GB     60GB
```

Verify Podman works:

```powershell
podman ps
podman compose version
```

If the last command errors with **`looking up compose provider failed`**,
continue to Part 3.5 below — you need to install a compose provider first.

---

## Part 3.5 — Install a Compose provider (Windows only)

Heads-up: `podman compose` (space) is a **dispatcher**, not a compose
implementation. On Windows, Podman Desktop does NOT bundle one by default,
so you have to install one. Pick ONE of the three options below.

### Option A — Podman Desktop "Compose" extension (recommended, no Python)

1. Open **Podman Desktop**.
2. Left sidebar → **Settings** → **Extensions**.
3. Find **Compose** → **Install**.
4. Restart Podman Desktop.
5. ⚠ **The extension does NOT add the binary to your PATH.** You must
   do it yourself — see the "Compose extension is active but CLI
   can't find it" entry in the Troubleshooting section below.
6. Verify (in a NEW PowerShell window after fixing PATH):
   ```powershell
   docker-compose --version
   podman compose version
   ```

### Option B — Python `podman-compose`

```powershell
# Make sure Python is present
python --version   # if missing:  winget install Python.Python.3.12

# Install podman-compose under your user
python -m pip install --user podman-compose

# Ensure the install dir is on PATH. pip prints a warning like:
#   "The script podman-compose is installed in
#    'C:\Users\<you>\AppData\Roaming\Python\Python312\Scripts'
#    which is not on PATH."
# Add that folder to PATH (System Properties → Environment Variables → PATH),
# open a NEW PowerShell window, then verify:

podman-compose --version
```

Either `podman compose -f podman-compose.yml ...` or
`podman-compose -f podman-compose.yml ...` will now work.

### Option C — Standalone `docker-compose.exe` from the official release

Single-file download, no installer, no Python, no extensions. Most
reliable on Windows once the Podman Desktop Compose extension misbehaves.

```powershell
# Folder for user-local binaries
$bin = "$env:USERPROFILE\bin"
New-Item -ItemType Directory -Force -Path $bin | Out-Null

# Official Docker Compose standalone (Windows x86_64, latest)
$uri = "https://github.com/docker/compose/releases/latest/download/docker-compose-windows-x86_64.exe"
Invoke-WebRequest -Uri $uri -OutFile "$bin\docker-compose.exe"

# Add to User PATH
$current = [Environment]::GetEnvironmentVariable("Path", "User")
if ($current -notlike "*$bin*") {
    [Environment]::SetEnvironmentVariable("Path", "$current;$bin", "User")
}
```

Close and reopen PowerShell, then `podman compose -f podman-compose.yml up -d`
works.

### Option D — Docker Desktop is already installed

If Docker Desktop is installed and running, its `docker compose` plugin
is auto-picked up by `podman compose` as the provider. Nothing extra to
do on top.

### Option E — No compose tool at all (script in this repo)

If you have Podman Desktop and don't want to install Python, the Compose
extension, or anything else, use `podman-run-infra.ps1` at the repo
root. It starts the same two containers as `podman-compose.yml` using
plain `podman run` commands:

```powershell
cd path\to\task-orchestrator
.\podman-run-infra.ps1 up          # start postgres + kafka
.\podman-run-infra.ps1 status      # list the containers
.\podman-run-infra.ps1 logs        # tail both logs
.\podman-run-infra.ps1 down        # stop and remove
```

This completely bypasses `podman compose`, the Compose extension, and
`podman-compose` — you only need `podman` itself on PATH.

### Verify (after any option)

```powershell
podman compose version                        # prints a version, not an error
podman compose -f podman-compose.yml up -d    # starts postgres + kafka
podman ps                                     # should show 2 containers
```

---

## Part 4 — Run the Task Orchestrator

### Start ONLY the infrastructure (Postgres + Kafka, no app)

The compose file contains exactly two services: `postgres` and `kafka`. Starting
it boots both and nothing else — it does NOT start the orchestrator app or any
mocks.

```powershell
cd path\to\task-orchestrator
make podman-up
```

Or without Make (if Make isn't installed), from the repo root:

```powershell
podman compose -f podman-compose.yml up -d
```

If you want just one of the two services for some reason:

```powershell
podman compose -f podman-compose.yml up -d postgres   # just Postgres
podman compose -f podman-compose.yml up -d kafka      # just Kafka
```

Wait 10 seconds, then verify both are up:

```powershell
podman ps
```

You should see 2 containers: `postgres` and `kafka`. Connection info:

| Service | Host     | Port  | Credentials                               |
|---------|----------|-------|-------------------------------------------|
| Postgres| localhost| 5433  | user `orchestrator` / pass `orchestrator` / db `orchestrator` |
| Kafka   | localhost| 9092  | PLAINTEXT, no auth                        |

Stop them when you're done:

```powershell
make podman-down
# or: podman compose -f podman-compose.yml down
```

### Start the app

```powershell
make run-podman
```

Or without Make:

```powershell
.\gradlew.bat :local-dev:bootRun
```

Wait for the log line:
```
All consumer groups ready. App is fully operational.
```

### Test it

Open a new PowerShell window:

```powershell
# Trigger a flow
curl -X POST http://localhost:8080/demo/start

# Wait 5 seconds

# Check barriers (expect 2 CLOSED)
curl http://localhost:8080/demo/barriers

# Check tasks (expect 3 COMPLETED)
curl http://localhost:8080/demo/tasks
```

### Inspect

| Tool | URL |
|------|-----|
| DBeaver | `localhost:5433` / `orchestrator` / `orchestrator` |

### Stop everything

```powershell
make podman-down
```

Or:

```powershell
podman compose -f podman-compose.yml down
```

---

## Part 5 — Run Tests with Podman

Testcontainers needs to connect to Podman's socket. Set these environment
variables before running tests:

```powershell
# Tell Testcontainers to use Podman's named pipe
$env:DOCKER_HOST = "npipe:////./pipe/podman-machine-default"

# Disable Ryuk (Testcontainers cleanup container — has issues with Podman)
$env:TESTCONTAINERS_RYUK_DISABLED = "true"

# Run all tests
.\gradlew.bat test

# Or just unit tests (no Podman needed)
.\gradlew.bat :task-orchestrator-app:test
```

---

## Troubleshooting

### "podman machine" fails to start

```powershell
# Check WSL is enabled
wsl --status

# If not installed
wsl --install

# Restart computer, then try again
podman machine stop
podman machine rm
podman machine init --cpus 4 --memory 4096
podman machine start
```

### Port conflict (5433 or 9092 already in use)

```powershell
# Find what's using the port
netstat -ano | findstr :5433
netstat -ano | findstr :9092

# Kill the process
taskkill /PID <pid> /F
```

### `looking up compose provider failed` (running `podman compose ...`)

`podman compose` (space) is a **dispatcher**, not a compose implementation.
It looks for one of these providers, in order: `docker compose` plugin,
`docker-compose.exe`, `podman-compose`. If none are installed — or if
one is installed but not on PATH — you hit this error.

**Fix:** install a provider — see **Part 3.5 — Install a Compose provider**.
The quickest path on Windows is the Podman Desktop "Compose" extension
(Settings → Extensions → Compose → Install). Note the extension alone is
NOT enough — see the next entry.

### Compose extension is **Active** in Podman Desktop but CLI still can't find it

This is the common Windows gotcha: the extension downloads
`docker-compose.exe` into its own storage folder under `%APPDATA%` but
does NOT add that folder to your PATH. `podman compose` still reports
`looking up compose provider failed` and `docker-compose --version`
returns "not recognized".

Find where the extension put the binary:

```powershell
Get-ChildItem -Path $env:APPDATA,$env:LOCALAPPDATA `
    -Recurse -Filter "docker-compose.exe" -ErrorAction SilentlyContinue |
  Select-Object FullName
```

Typical result:
```
C:\Users\<you>\AppData\Roaming\Podman Desktop\extensions-storage\compose\bin\docker-compose.exe
```

Append that folder to your User PATH (once, persists across restarts):

```powershell
$bin = "C:\Users\<you>\AppData\Roaming\Podman Desktop\extensions-storage\compose\bin"
$current = [Environment]::GetEnvironmentVariable("Path", "User")
if ($current -notlike "*$bin*") {
    [Environment]::SetEnvironmentVariable("Path", "$current;$bin", "User")
}
```

Close and reopen PowerShell (PATH is read at shell startup). Then:

```powershell
docker-compose --version            # prints a version
podman compose version              # dispatcher now finds the provider
podman compose -f podman-compose.yml up -d
```

If you don't want to manage PATH at all, use **Option B** in Part 3.5
(`pip install --user podman-compose`) instead — fewer moving parts.

### `'podman-compose' is not recognized` / `executable file not found in %PATH%`

You typed `podman-compose` (hyphen — the separate Python tool) but it
isn't installed. Two clean options:

1. Use the dispatcher instead, after installing a provider per Part 3.5:
   ```powershell
   podman compose -f podman-compose.yml up -d
   ```
2. Install the Python tool itself and add its folder to `PATH`:
   ```powershell
   python -m pip install --user podman-compose
   podman-compose -f podman-compose.yml up -d
   ```

`podman compose` (space) and `podman-compose` (hyphen) are different
binaries. Every command in this doc and in the project's `Makefile` uses
the space form.

### "Cannot connect to Podman" in Testcontainers

```powershell
# Verify the Podman machine is running
podman machine list

# Check the socket path
podman machine inspect | findstr "PipePath"

# Set DOCKER_HOST to match
$env:DOCKER_HOST = "npipe:////./pipe/podman-machine-default"
```

### Kafka container exits immediately

```powershell
# Check logs
podman logs orchestrator-kafka-1

# If it's a memory issue, increase machine memory
podman machine stop
podman machine set --memory 6144
podman machine start
```

### Make is not installed on Windows

Install via Chocolatey:

```powershell
choco install make
```

Or use Git Bash (comes with Git for Windows) which includes Make.

Or run the commands directly without Make:

```powershell
# Instead of: make podman-up
podman compose -f podman-compose.yml up -d

# Instead of: make run-podman
.\gradlew.bat :local-dev:bootRun

# Instead of: make podman-down
podman compose -f podman-compose.yml down

# Instead of: make test
.\gradlew.bat test

# Instead of: make build
.\gradlew.bat clean build
```

---

## Quick Reference

| Command | What it does |
|---------|-------------|
| `podman machine init` | Create the VM (one-time) |
| `podman machine start` | Start the VM |
| `podman machine stop` | Stop the VM |
| `podman machine list` | Show VM status |
| `podman ps` | List running containers |
| `podman compose -f podman-compose.yml up -d` | Start infrastructure |
| `podman compose -f podman-compose.yml down` | Stop infrastructure |
| `podman logs <container-name>` | View container logs |
