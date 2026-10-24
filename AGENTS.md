# Blank — Agent Guidelines

See [GEMINI.md](file:///d:/Blank/GEMINI.md) and [.agents/workflows/blank-guidelines.md](file:///d:/Blank/.agents/workflows/blank-guidelines.md) for full workspace rules.

## Core Rules Summary
1. **Goal**: System-wide Brave-like ad & tracker blocking for Android via local loopback `VpnService` (port 53 DNS interception).
2. **Current Focus**: Phase 1 stability only (VpnService + DNS synthesis + upstream forwarding + anti-VPN bypass + battery efficiency).
3. **Out of Scope**: No backend, no leaderboards, no cloud dashboard.
4. **Privacy**: Zero logging of domains, URLs, or packages. Local aggregate counters only.
5. **Storage**: All builds, SDKs, and Gradle caches must remain on **D: drive**. Never write to C: drive.
