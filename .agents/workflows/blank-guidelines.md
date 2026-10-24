---
description: 
---

# Blank — Workspace Rules

## Project
Blank: Android app, system-wide ad/tracker blocker using local-loopback VpnService (no root).
Repo: github.com/posathanmai1-lang/Blank
Leaderboard/dashboard is a LATER phase — not now.

## Build order (strict)
- Phase 1 (current, only focus): VpnService + DNS-level blocking core. Get this working reliably on-device before anything else.
- Phase 2 (later, do not scaffold yet): local counter/stats already tracked in Phase 1, but NO backend sync, NO leaderboard, NO dashboard until Phase 1 is stable and tested.
- Do not suggest or scaffold leaderboard/backend/dashboard code until explicitly asked.

## Core architecture constraints
- Use Android's VpnService API with a local TUN interface routed to 10.0.0.2 (self, not remote) — traffic never leaves the device.
- DNS-level filtering ONLY for v1. No packet/SNI-level inspection — out of scope until v2.
- Block by returning NXDOMAIN / 0.0.0.0 for domains matching the blocklist; forward everything else to a real upstream resolver unmodified.
- Blocklist source: start from a public hosts-file/EasyList-style DNS list. Keep the matcher swappable so the list can update without a rebuild.

## Lightweight-first priorities (apply to every suggestion)
- Smallest dependency footprint possible. Kotlin stdlib + Android SDK first; only pull in a library if hand-rolling is genuinely unsafe or unreasonable.
- Blocklist matching must be O(1) or close to it — HashSet/Trie for domain lookups, not linear scans.
- Background service must be battery-conscious: no busy-wait loops, no unnecessary wakeups, minimize allocations in the packet-read loop.
- Counter increments go to local storage (SharedPreferences or Room) first — this is Phase 1 groundwork even though sync comes later.

## Privacy (non-negotiable)
- NEVER log, store, or transmit a domain name, URL, app package, or timestamp tied to a specific request.
- When sync is eventually built (Phase 2), only an aggregate count per period leaves the device — nothing more.

## Environment constraints (strict)
- Gradle installation lives on D drive, not C drive.
- NEVER touch or write to C drive unless absolutely necessary — all project files, builds, caches, SDK paths default to D drive.
- Before running any build/setup command, verify paths point to D drive. If a tool defaults to C drive (common with Android Studio/Gradle installers), explicitly override the path.
- If C drive access is genuinely unavoidable, flag it clearly and explain why before proceeding.
- Watch for ANDROID_SDK_ROOT / ANDROID_HOME / GRADLE_USER_HOME silently defaulting to C — set these explicitly to D-drive paths.

## Style
- Kotlin, idiomatic Android, no unnecessary abstraction layers for a solo-dev v1.
- Copy-pasteable, complete code blocks — no partial snippets or pseudocode unless explicitly asked.
- Casual, direct explanations. Skip conceptual filler.