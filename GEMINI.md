# Blank — Ad/Tracker Blocker for Android

## Project Mission & Vision
Blank is an Android application that provides **Brave Browser-like ad and tracker blocking across the entire mobile operating system** (all apps, browsers, games, and web views) without requiring root permissions.
- **Mechanism**: Native Android `VpnService` running on a local loopback interface (`10.0.0.2`), capturing port 53 DNS requests locally.
- **100% On-Device & Zero-Telemetry**: Traffic never leaves the device. Blocked domains are answered instantly with `0.0.0.0` (RFC DNS response), and legitimate queries are forwarded to active physical network DNS servers (carrier 5G/4G or Wi-Fi).

## Strict Build Order & Phases
- **Phase 1 (CURRENT FOCUS)**: 
  - Stabilize local `VpnService` + DNS filtering engine.
  - Ensure zero crashes, low latency, robust memory management.
  - Maintain anti-VPN app exemptions (Hotstar, JioCinema, banking, UPI apps).
  - Verify foreground service lifecycle and notification handling.
  - DO NOT scaffold or suggest backend sync, leaderboards, or web dashboards until Phase 1 is tested and signed off.
- **Phase 2 (FUTURE)**:
  - Dynamic blocklist updates from remote sources (e.g., StevenBlack / OISD).
  - App-level exclusion UI.
  - Only when requested: optional, privacy-safe aggregate block count sync.

## Git & Collaboration Rules
- **Branch Protection Active**: Direct pushes to `main` are protected and restricted.
- All work must be done on dedicated feature branches (e.g. `feature/phase1-stability`, `feature/notification-lifecycle`).
- Commit messages must be clear and conventional (e.g. `feat: ...`, `fix: ...`).
- When work is ready, push the feature branch to origin (`git push -u origin feature/...`) so the repo owner can review and merge via Pull Request.

## Non-Negotiable Privacy Rules
1. NEVER log, store, or transmit domain names, URLs, app package names, or query timestamps.
2. Only track in-memory / local aggregate numeric counters (Blocked Count, Total Queries).

## Environment & File System Constraints
- **Drive Constraint**: All project files, Gradle caches, builds, and Android SDK configs MUST live on the **D: drive** (e.g., `D:\...`).
- NEVER write to or pollute the `C:` drive unless strictly requested or unavoidable.
- Set `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME` to D-drive paths.

## Code Standards
- Idiomatic Kotlin, Android SDK + Kotlin stdlib first (keep APK lightweight, minimal external dependencies).
- O(1) domain lookups using HashSets / optimized Tries.
- High-efficiency packet loop: avoid allocations in read/write hot loops.
