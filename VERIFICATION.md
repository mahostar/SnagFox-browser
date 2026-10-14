# Snagfox Windows 0.1.3 verification

Built locally on 2026-10-14 with the original Java engine, cached Electron/runtime/media tools and no dependency downloads. Android workspace remained read-only.

## Verified on the installed 0.1.3 application

- Setup installation exit code 0 into verification/installed-0.1.3.
- Independent offline smoke suite: 29 logged steps pass.
- Cookie-aware direct transfers, validated Range/resume, interruption retry, HLS, static DASH assembly, MP3 conversion, full media decode and queue recovery after Java termination pass.
- All internal pages, light/dark appearance, 125% UI scale, website IPC isolation and library playback pass.
- Exact installed file-range handler: four valid ranges, five invalid ranges, HEAD/full response, cancellation, empty files and method rejection pass.
- Actual styled address popup Copy, Paste and Paste and Go pass on the current source build. Electron asynchronous clipboard API mismatch was reproduced and fixed.
- Host rename transaction rejection/lost reply/collision/success regression passes.

## Implemented; remaining verification boundary

Subtitles are collapsed by default with bounded searchable expansion; overflow/protection/tab/address menus share the browser theme; the footer is removed; four progress bars advance together from measured overall progress; tab wheel scrolling has inertia; tabs support pointer drag reordering. No independent per-worker telemetry is fabricated.

The full input suite stops at an embedded website link: synthetic pointer injection produced no website events despite a visible, focused view. That failure is recorded, not counted as a pass. Downstream drag/subtitle/momentum assertions in that full run are not established by an incomplete suite.

Local-video protocol now serves proper byte ranges. A dedicated player test has been added for exact range bodies, seeking to75% and25%, continued playback and mouse-operated +/-5 seconds; its final packaged run is pending at the time this preview is published. A preview label is used instead of claiming all requested flows are verified.

Live YouTube/Music extraction and Internet site coverage were not newly verified under offline development. Existing native Java routes, transformations, session ownership and SABR remain preserved; player-signature logs supplied by the user do not prove universal current-site success.

## Delivery and rebuild

Installer: delivery/Snagfox-Setup.exe.
Editable source: this repository.
Short commands: README.md.
Offline runtime inputs: matching private release Snagfox-Build-Inputs.zip, including checksum manifest.
The existing repository remains private. Public binary redistribution needs the exact corresponding FFmpeg/dependency and OpenJDK sources described in THIRD_PARTY_NOTICES.md.

No reference APKs, original applications, raw analysis dumps, browser profiles, cookies or personal download queues are published.
