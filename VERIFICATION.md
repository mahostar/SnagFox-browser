# Snagfox Windows 0.1.2 — functional repair and independent review

Date: 2026-10-14. This report supersedes the original 0.1.0 report, retained in `verification/VERIFICATION_0.1.0_ARCHIVE.md`.

The original delivery's engine fixture checks did not adequately test the user-facing controls. The user's reported failures were reproduced and repaired; actual installed mouse/keyboard workflows and an independent critic now provide separate evidence.

## Final delivery

- Installer: `delivery/Snagfox-Setup.exe`, 192,027,333 bytes.
- Version: **0.1.2**.
- SHA-256: `4c1168a8b337343dd14b230c4c5b439bf68d6da4b2523436a68dde8542f09719`.
- Independently tested installation: `verification/installed-0.1.2/Snagfox.exe`.
- User installation updated in place: `C:\Users\Mohamed\AppData\Local\Programs\Snagfox\Snagfox.exe`.
- Existing profile JSON backed up under `verification/user-profile-before-0.1.2`; the application profile/history/session data was retained.
- Installation logs: `verification/install-0.1.2.log` and `verification/user-install-0.1.2.log`.
- Source, stage, independently tested installation and user installation match for 11 host/UI/engine/runtime files: `verification/installed-file-integrity-0.1.2.json`.

## Failure-to-fix checklist

| Reported failure | Repair | Runtime/regression evidence |
|---|---|---|
| Plus did not open a new tab | Home is no longer a singleton new-tab target; Plus and Ctrl+T always create a tab | Real held mouse click and keyboard test from Home |
| Clicks, hover and focus were unreliable | Background polling patches existing nodes instead of replacing controls | Button identity and held-click checks spanning multiple polls; Ctrl+L focus/text retained |
| Only Close visibly hovered; inconsistent control boxes | Uniform toolbar and window-button group sizes; explicit hover/pressed/focus states; red Close retained | Actual pointer hover and computed dimensions for each group; visual review |
| Browser arrows stopped working after Home | Home keeps the previous Chromium page/history; Back/Forward restore it | Omnibox, page link, Back, Forward, Alt+Left, Reload and Home excursion tested through input events |
| Enter navigation was fragile | Explicit Enter navigation for omnibox/Home search, with IME/repeat/duplicate-submit guards | Typed local URL and actual Enter key input |
| Crowded tabs had no usable scrolling | Left/right overflow arrows, horizontal wheel scrolling and active-tab reveal | Actual wheel displacement, arrow scrolling, keyboard reveal, overflow selection/close at 125% |
| Protection had no dropdown | Native global/per-site protection menu and current-tab blocked count | Actual toolbar click opens native menu; actual menu callbacks toggle persisted settings |
| Download arrow had no useful action on Home | Native menu offers inspection, paste, Downloading and Downloaded files; website click opens one contextual media panel | Menu opening and chooser open/close/reopen checks |
| Active and finished downloads were unclear | Separate Downloading and Downloaded files headings, navigation, counts and card types | Real chooser → active queue → Pause/Resume → completed file → finished library → player |
| YouTube/Music failed with non-finite JSON | Missing audio FPS from Java optDouble becomes JSON null at the desktop bridge; valid FPS preserved | Exact missing-FPS→NaN→JSON exception reproduced, repaired serialization tested in installed JAR |
| Unknown MP4 label | Original parser extension inference applied to unknown hints; UI fallback is explicitly qualified | MP4/M4A/extensionless/query-only tests and inspected installed chooser/library screenshots |
| Paused/processing download showed stale speed | Rate displayed only during actual network-downloading phase | Paused row/footer and processing stage/progress assertions; paused screenshot visually inspected |
| Critic-found rename failure broke library path | Metadata commit precedes original-file removal; rejected commit rolls back copy; uncertain commit preserves data and reconciles known reply loss | Six real-filesystem cases extracted from exact installed main branch, plus installed Java collision/commit tests |
| Duplicate/stale dialogs or async results | Chooser guards, cancellation rejection handling and player ownership checks | Repeated chooser/input checks; code review and Java cancellation tests |

The Java native engine remains Java. Its original parser, native YouTube clients, player transformations, token provider, chunked transport, SABR, HLS/DASH and transfer/recovery classes were not rewritten. Changes in this repair target the desktop bridge, host and UI. Four Android platform classes remain the existing Java desktop adaptations from the initial build. All 193 copied references still match the mobile workspace.

## Independent critic protocol

Three medium-reasoning subagents were used: UI specialist, Java/backend specialist, and an independent installed-app tester/critic. The critic was asked to inspect rendered UI as well as execute tests. Maximum three rounds; repeat required below 8.

| Round | Overall | Visual | Outcome |
|---|---:|---:|---|
| 1 | 7.8/10 | 8.8/10 | Host rename commit issue and two display issues found; repaired |
| 2 | **8.6/10** | **8.9/10** | All round-one findings resolved in tested local scope; third round not indicated |

These are the critic's judgments, not website-coverage percentages or a guarantee of defect-free operation. Reports: `verification/CRITIC_ROUND_1.md`, `verification/CRITIC_ROUND_2.md`. Detailed specialist notes: `UI_FIXES_0.1.1.md`, `BACKEND_FIXES_0.1.1.md`.

## Independently executed installed checks

- **19 grouped interaction checks**: actual mouse down/up, held clicks, keyboard and wheel events; tab/nav controls, hover/focus, 125% scale, actual media choice, cookie-protected transfer, pause/resume, final SHA-256 and local playback. `verification/critic-r2-ui/PASS.json`, `interactions.json`, screenshots and logs.
- Installed engine suite: actual cookie/Referer handling, direct Range/If-Range resume, HTTP503 retry, ignored Range, cut responses, finite HLS, static DASH with separate tracks, FFmpeg assembly/decode, MP3/decode, Java-process termination recovery. `verification/critic-r2-engine`. Its 29 logged steps include two progress messages and must not be described as 29 independent feature tests.
- Original Java signature/n helpers, synthetic BotGuard VM, SABR custom frames/deduplication/checkpoint restoration, SRT recipe and nonfinite metadata contract checks are included. Synthetic protocol fixtures are not live platform coverage.
- Whole installed Electron/Java process tree killed and reopened against an independent loopback server: runnable task recovered byte-exactly; explicitly paused task remained paused. `verification/critic-r2-recovery/PASS.json`.
- Exact installed main branch rename tests: rejection, engine offline, uncommitted lost reply, committed lost reply, success and filename collision. `verification/critic-r2-rename/PASS.json`.
- Actual installed Java JAR pure bridge fixture: nonfinite/finite serialization, private recipe exclusion, 1,000 quick completions, cancellation/interruption/ownership, MIME inference and transactional Java rename. `verification/critic-r2-rename/installed-java-fixture.log`.

All byte-exact fixture paths reproduced the 3,358,149-byte original MP4, SHA-256 `8a75946fd5d9e3e8a5664527b6a0bde443b6ef41bee75cefa436d2bd925fdbf6`. HLS, DASH and MP3 outputs were fully decoded by bundled FFmpeg.

## Visual review and limits

The critic viewed six actual installed screenshots, including Home, Settings, media chooser, paused queue, finished files and crowded tabs. The accepted dark design remains cohesive and unchanged in direction. MP4 labels and paused speeds were visually checked. Native website content is a separate WebContentsView; shell capturePage screenshots do not composite that child surface, so a blank viewport in such an artifact is not evidence of a blank browser.

Native menu opening uses real mouse input; automated menu selection invokes native MenuItem callbacks, not OS pointer hit-testing. Processing-state rendering is also checked with a synchronous renderer-only fixture. Light theme is asserted, not independently visually scored. Native file-dialog gestures, tray gestures, physical monitor/DPI combinations, cold boot and separate-PC acceptance remain unverified.

Development stayed offline. The user's logs establish that route3 reached ready before the reported JSON bridge exception; they do not establish completed downloads. The exact exception is reproduced and fixed, but current live YouTube/Music authorization/downloads, current player-script coverage, live subtitles, real BotGuard challenges and broad website/ad-protection coverage remain unverified. No NewPipe/yt-dlp replacement, dependency download, upload, push, publication or deployment occurred. The original Android workspace was not edited or rebuilt.
