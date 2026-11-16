# Environment Report

Verified: 2026-11-15

## Machine

| Item | Value |
|---|---|
| OS | Nobara Linux 44 (Fedora 44 base, KDE Plasma) |
| Kernel | 7.2.11-200.nobara.fc44.x86_64 |
| Architecture | x86_64 |
| CPU/RAM | 15 GiB RAM |
| Disk (project) | 681 GB free on `/home` |
| Network | Maven Central `200`, GitHub API `200` |

## Toolchain

| Tool | Found | Version | Requirement | Status |
|---|---|---|---|---|
| Java runtime | yes | OpenJDK 25.0.4.1 (Red Hat build) | >= 21 | PASS |
| Java compiler | yes | javac 25.0.4.1 | >= 21 | PASS |
| Git | yes | 2.56.0 | any | PASS |
| Docker engine | yes | 29.8.2 (API 1.56) | any | PASS |
| Docker Compose | yes | v5.7.1 | v2+ | PASS |
| Docker daemon | yes | active, `hello-world` verified | running | PASS |
| Maven | no global install | via Maven Wrapper (`./mvnw`) | wrapper only | PASS (by design) |
| k6 | yes | v2.4.0 (user-local `~/.local/bin/k6`) | Phase 5 | PASS (installed from the official release tarball, no sudo) |
| GitHub CLI | yes | 2.97.0, authenticated as `vishweshpenkar06` | optional | PASS |

## Manual steps performed by the human

1. `sudo dnf install -y java-25-openjdk-devel` — installed JDK 25 (newest LTS, matches the
   installed runtime). `javac` now available.
2. `sudo usermod -aG docker $USER` — added user to the `docker` group.
   A log-out/log-in was required for the group to apply to interactive sessions.
   Until the session is restarted, commands in this environment use `sg docker -c "..."`.

## Notes / constraints

- **Port 5432 is already in use** by an unrelated running container (`dinesense-db-1`).
  RepoPrism' `docker-compose.yml` therefore maps Postgres to host port **55432** to avoid a
  collision. See `docs/DECISIONS.md` (ADR-0003).
- Docker group membership is present in `/etc/group` (`docker:x:972:vishwesh`). New shells
  after re-login have access without `sg`.
- No JDK 21 package exists in the Nobara repos; JDK 25 is the newest LTS and satisfies the
  plan's "Java 21 minimum, newest LTS supported" rule. The chosen Spring version's JDK
  support is verified separately in ADR-0001.
- k6 is not required before Phase 5 and was intentionally not installed yet.
