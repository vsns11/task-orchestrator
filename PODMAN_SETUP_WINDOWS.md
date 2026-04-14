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

---

## Part 4 — Run the Task Orchestrator

### Start infrastructure

```powershell
cd path\to\task-orchestrator
make podman-up
```

Or without Make (if Make isn't installed):

```powershell
podman compose -f podman-compose.yml up -d
```

Wait 10 seconds, then verify:

```powershell
podman ps
```

You should see 3 containers: postgres, kafka, kafka-ui.

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
| Kafka UI | http://localhost:9091 |
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

### Port conflict (5433, 9092, or 9091 already in use)

```powershell
# Find what's using the port
netstat -ano | findstr :5433
netstat -ano | findstr :9092

# Kill the process
taskkill /PID <pid> /F
```

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
