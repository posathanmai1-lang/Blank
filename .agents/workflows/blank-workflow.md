---
description: 
---

# Phase 1 Autonomous Development & PR Workflow

1. Branch Hygiene:
   - Always verify current branch before editing: `git status`.
   - Never push directly to `main`. Create or use `feature/<task-name>`.

2. Environment & Drive Validation:
   - Confirm `ANDROID_HOME`, `ANDROID_SDK_ROOT`, and `GRADLE_USER_HOME` resolve to D-drive paths.
   - Verify `local.properties` specifies `sdk.dir=D\:\\Android\\Sdk` (or your local D-drive SDK path).

3. Phase 1 Implementation Priorities:
   - Foreground notification implementation (`POST_NOTIFICATIONS` permission + `FOREGROUND_SERVICE` type) to prevent OS process killing.
   - Scale `blocklist.txt` from starter list to expanded list without memory leaks ($O(1)$ lookups via ConcurrentHashMap).
   - Network state receiver: cleanly handle Wi-Fi to Cellular (5G/4G) handovers without dropping the local VPN interface.

4. Terminal Verification:
   - Run `./gradlew assembleDebug` to test compilation.
   - Ensure zero build warnings or lint crashes.

5. Ship via Pull Request:
   - Stage modified files: `git add <files>`.
   - Commit: `git commit -m "feat(phase1): <concise description>"`.
   - Push branch: `git push -u origin feature/<task-name>`.
   - Notify the user with the GitHub Pull Request URL for owner review.