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

### 💬 Prompt A: Chat Interface Prompt (For your friend to paste in Antigravity)
> Copy and paste this directly into the Antigravity chat window on your friend's PC:

```markdown
Hello Antigravity! I am taking over development of the Blank project.

Our core mission: System-wide Brave Browser-like ad and tracker blocking on Android mobile devices using a 100% on-device local VpnService (no root, zero telemetry, local DNS synthesis).

Please read `GEMINI.md`, `AGENTS.md`, and `.agents/workflows/blank-guidelines.md` before doing anything.

Key Constraints:
1. We are in Phase 1 (VPN stability & DNS filtering on-device). Do NOT scaffold any backend, cloud sync, or leaderboards.
2. Privacy is non-negotiable: NEVER log domains or app names. Only aggregate numeric stats.
3. Drive constraint: All Gradle caches, Android SDK, and builds MUST remain on D: drive. Do not write to C: drive.
4. Lightweight first: Kotlin + Android SDK first. No bulky 3rd party libraries.

Please review `HANDOFF_AND_PROMPTS.md` and `app/src/main/java/com/blank/app/` to understand the current code, and tell me:
1. Is the project ready to build right now with Gradle?
2. What are the top 3 immediate tasks we need to complete for Phase 1 stabilization?
```

---

### 🛠️ Prompt B: Workflow / Build Prompt (To test & build the APK)
```markdown
Check our Android SDK and Gradle setup on D: drive, verify that `local.properties` correctly points to the D-drive Android SDK, and run `./gradlew assembleDebug` to test the build. If there are any compiler or manifest errors, diagnose and resolve them.
```

---

### 📋 Prompt C: Workspace Rules & Context Summary
> (Already configured in `GEMINI.md` and `AGENTS.md` in the project root)
- **Role**: High-performance, lightweight Android network and DNS filtering tool.
- **Rule 1**: Phase 1 focus only.
- **Rule 2**: Strict privacy.
- **Rule 3**: D: Drive environment only.
- **Rule 4**: Fast $O(1)$ lookups, battery-friendly event loops.

---

## 5. Local Environment Checklist for Friend's Machine
- [ ] Clone repo to `D:\Blank` (must be on D: drive).
- [ ] Ensure `local.properties` has `sdk.dir=D\:\\Android\\Sdk` (matching friend's D-drive SDK path).
- [ ] Open folder `D:\Blank` in Antigravity IDE.
- [ ] Antigravity will automatically pick up `GEMINI.md`, `AGENTS.md`, and `.agents/workflows/blank-guidelines.md`.
