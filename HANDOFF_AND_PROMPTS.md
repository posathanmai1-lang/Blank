# Blank — Project Handoff & Developer Continuity Guide

Welcome to **Blank**! This guide is prepared so any developer opening this repository in **Antigravity** can immediately understand the project state, architecture, and step right into the development flow.

---

## 1. Project Vision & Architecture

**Goal**: Attain Brave Browser-like ad and tracker blocking across the **entire mobile operating system** (all browsers, apps, games, and web views) through a lightweight, 100% on-device Android app.

### How it works:
- **No Root Required**: Uses Android's native `VpnService` to establish a local-only virtual network interface (TUN) bound to `10.0.0.2/32`.
- **Zero Remote VPN / Zero Telemetry**: Network traffic never leaves the phone to a third-party server. Only DNS packets (UDP port 53) are intercepted.
- **Instant Synthesized Blocking**: If a domain matches the blocklist, the app immediately returns a synthesized DNS response with `0.0.0.0` (Type A) or `NODATA` (Type AAAA) with valid IP/UDP checksums.
- **Physical Network Upstream Forwarding**: Allowed queries are forwarded via protected UDP sockets to active physical network DNS servers (carrier 5G/Wi-Fi) with public DNS fallbacks (`1.1.1.1`, `8.8.8.8`).
- **Anti-VPN App Bypass**: Media and banking apps with strict anti-VPN detections (JioHotstar, JioCinema, Netflix, Amazon Prime, GPay, Paytm, PhonePe) are bypassed via `addDisallowedApplication()`, preventing `NET_100` errors.

---

## 2. Project Status Breakdown

### ✅ What Was Built (Phase 1 Core)
1. **Gradle & Android Base**:
   - SDK 34 compile, Kotlin, minSdk 26.
   - Ultra-lightweight dependencies: AndroidX Core, Activity KTX, AppCompat (no bloated 3rd-party libs).
2. **`BlankVpnService.kt`**:
   - Local loopback TUN setup (`10.0.0.2`).
   - UDP 53 DNS interception loop with thread pool (`Executors.newFixedThreadPool(4)`).
   - QUIC drop (UDP 443) to force standard DNS/TCP resolution fallback.
   - Bypassing strict anti-VPN apps.
3. **`DnsPacketParser.kt`**:
   - Zero-copy RFC 1035 QNAME DNS question parsing from raw IPv4 UDP packets.
4. **`BlocklistMatcher.kt`**:
   - `ConcurrentHashMap.newKeySet()` for $O(1)$ domain checks.
   - Suffix matching (`ads.unity3d.com` -> checks `ads.unity3d.com`, then `unity3d.com`).
   - Preloaded `whitelist.txt` and `blocklist.txt` from assets.
5. **`DnsPacketSynthesizer.kt`**:
   - Crafts RFC-compliant raw byte responses with computed IPv4 header checksums.
6. **`UpstreamDnsResolver.kt`**:
   - Carrier/Wi-Fi DNS discovery + protected socket forwarding + Cloudflare/Google fallbacks.
7. **`StatsManager.kt`**:
   - Atomic counters (`blockedAds`, `totalQueries`) saved to `SharedPreferences`.
8. **`MainActivity.kt` & UI**:
   - Modern dark UI, real-time status indicator (Active / Inactive), start/stop toggle, live block/query counters.

---

### ⏳ What Is In Progress (Immediate Phase 1 Polish)
1. **On-Device Testing & Verification**:
   - Test on real Android hardware or emulator with mobile data (Jio/Airtel) and Wi-Fi.
   - Validate that ad-heavy apps (e.g. news apps, casual games) show blocked ads while regular browsing has zero delay.
2. **Foreground Service Notification**:
   - Add persistent notification required by Android 13/14+ (`POST_NOTIFICATIONS` permission and `FOREGROUND_SERVICE` type) to ensure OS does not kill the service in background.
3. **Blocklist Scale**:
   - Replace starter `blocklist.txt` (~1.4 KB) with an expanded hosts file (e.g., StevenBlack base list ~40k-60k domains) while monitoring RAM consumption.

---

### ❌ What Is Pending (Phase 2 & Beyond — DO NOT IMPLEMENT YET)
- **App-level Whitelist/Blacklist UI**: Letting the user toggle specific installed apps in the UI.
- **Dynamic Remote Blocklist Updating**: In-app download of updated blocklists without updating the APK.
- **DoH / DoT (Encrypted DNS)**: DNS-over-HTTPS or DNS-over-TLS upstream resolution.
- **Leaderboard / Dashboard / Cloud Sync**: Only after Phase 1 is fully battle-tested and stable. (Privacy constraint: aggregate anonymous counts only).

---

## 3. Retaining 100% Code Ownership with GitHub

To share this project with your collaborator while keeping **100% legal and technical ownership**:

1. **Create the GitHub Repo under YOUR Account**:
   - Repo: `https://github.com/posathanmai1-lang/Blank` (or private repository).
   - Ensure you are the sole **Owner** of the repository.
2. **Add a Proper License**:
   - Add a proprietary `LICENSE` file (e.g., "Copyright (c) 2025-2026. All rights reserved. Proprietary software.") or an explicit contributor agreement if keeping it closed-source.
3. **Invite your friend as a Collaborator**:
   - GitHub Repo -> **Settings** -> **Collaborators** -> **Add people**.
   - Your friend gets push access, but **YOU** own the repo, control settings, issues, and releases.
4. **Enforce Branch Protection (Recommended)**:
   - GitHub Repo -> **Settings** -> **Branches** -> Add rule for `main`.
   - Require Pull Requests (PRs) before merging: your friend works on branch `feature/phase1-improvements` and creates a PR. You review and merge it. This ensures you inspect and own every line merged into `main`.

---

## 4. Prompts Ready for Antigravity

### 💬 Prompt 1: Chat Interface Prompt (Paste into Antigravity Chat)
> Your friend copies and pastes this directly into Antigravity chat. Antigravity will use its built-in terminal to check git, setup a branch, verify the D-drive SDK, test the Gradle build, and outline next steps without your friend having to type manual commands.

```markdown
Hello Antigravity! I am collaborating on the Blank project with repo owner posathanmai1-lang.

Our Vision:
System-wide Brave Browser-like ad and tracker blocking on Android mobile devices using a 100% on-device local VpnService (10.0.0.2 loopback, UDP port 53 DNS interception, zero telemetry, RFC 0.0.0.0 synthesis).

Collaboration & GitHub Access:
- I have accepted collaboration on the GitHub repository: https://github.com/posathanmai1-lang/Blank
- IMPORTANT: The `main` branch has branch protection enabled. Direct pushes to `main` will be rejected by GitHub.
- You (Antigravity) must handle all git operations via your terminal tools: create a new feature branch (e.g. `feature/phase1-stability`), commit changes with conventional commit messages, and push the branch to origin (`git push -u origin feature/...`) so the owner can review and merge via Pull Request.

Workspace Constraints:
1. Phase 1 Only: Local VpnService + DNS synthesis + upstream forwarding + anti-VPN bypass + foreground service stability. No backend, no leaderboards, no dashboards.
2. Privacy: Zero logging of domains, URLs, or packages. Local aggregate counters only.
3. Storage: All builds, SDKs, and Gradle caches must remain on D: drive. Never write to C: drive.
4. Minimal Dependencies: Kotlin stdlib + Android SDK first.

Please run terminal commands right now to:
1. Verify git status and current branch, and checkout a new branch `feature/phase1-stability`.
2. Verify that Android SDK and Gradle paths on D: drive are properly set in `local.properties`.
3. Run `./gradlew assembleDebug` to test if the project compiles cleanly.
4. Report back the build status and what immediate Phase 1 hardening tasks you will begin.
```

---

### 🛠️ Prompt 2: Workflow Workspace Prompt (For `.agents/workflows/` or workflow runner)
```markdown
# Phase 1 Development & Verification Workflow
1. Branch Setup: Always ensure working branch is `feature/<feature-name>`. Never commit directly to `main`.
2. Environment Check: Ensure `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME` point to D-drive paths. Ensure `local.properties` exists on D drive.
3. Code Implementation: Implement Phase 1 features (e.g., foreground notification, blocklist scaling, network switch reconnection) keeping $O(1)$ lookups and zero hot-loop allocations.
4. Local Validation: Run `./gradlew assembleDebug` in terminal to verify zero compilation errors and test APK creation.
5. Push & PR: Run `git add .`, commit with descriptive message, and push the feature branch (`git push -u origin feature/<feature-name>`) for owner review.
```

---

### 📋 Prompt 3: Workspace Rules Prompt (For `GEMINI.md` or IDE workspace rules settings)
```markdown
# Blank Workspace Rules
- Role: Native Android Systems Engineer working as a collaborator on `posathanmai1-lang/Blank`.
- Vision: System-wide Brave-style ad/tracker blocker using Android VpnService (10.0.0.2 loopback, port 53 DNS capture, RFC 0.0.0.0 reply, zero remote servers).
- Strict Build Phase: Phase 1 only. Do NOT scaffold cloud sync, leaderboards, or web dashboards.
- Privacy: Zero logging of visited domains or apps. Local aggregate counters only.
- Git Rules: `main` branch is protected. All work must be committed and pushed on `feature/*` branches for owner PR review.
- Storage & Paths: D: drive strictly for all SDKs, Gradle caches, and build outputs. C: drive is strictly off-limits.
```

---

## 5. Local Environment Checklist for Friend's Machine
- [ ] Clone repo to `D:\Blank` (must be on D: drive).
- [ ] Ensure `local.properties` has `sdk.dir=D\:\\Android\\Sdk` (matching friend's D-drive SDK path).
- [ ] Open folder `D:\Blank` in Antigravity IDE.
- [ ] Paste Prompt 1 into the chat window — Antigravity will autonomously inspect the setup and compile!

