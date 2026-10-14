# Java preservation and desktop boundaries

The user's instruction forbids rewriting the complex engine into another language. The Windows application keeps the actual Java implementation. Browser/Windows integration is a wrapper around it.

Unchanged Java files (byte-identical to the copied Android source):

- BrowserProtection.java
- DownloadPolicy.java
- DownloadStore.java
- ManifestEnvelope.java
- ManifestRequestPolicy.java
- MediaCandidate.java
- MediaSelection.java
- SnapTubePlayerScript.java
- SnapTubePoToken.java
- SnapTubeYoutube.java
- YoutubeCaptions.java
- YoutubeDiagnostics.java
- YoutubeIO.java
- YoutubeMedia.java
- YoutubeResolver.java
- YoutubeSabr.java
- YoutubeWire.java

`DownloadService.java` keeps its existing transfer Worker, direct ranges/checkpoints/recovery gates, HTTP renewal, HLS/DASH fragments, YouTube chunk/SABR transport and phase reporting. Android foreground-service/notification lifecycle is replaced with a desktop-owned queue instance; wake-lock, Wi-Fi-only and gallery behavior are removed. The original `DownloadStore` stays Java, including durable queue storage, identity validation and filename sanitization. Persisted legacy fields remain data compatibility fields and have no vault/private-folder UI.

`MediaRemuxer`, `Mp3Export` and `MediaExport` are desktop Java adapters. Android MediaCodec/MediaMuxer cannot run on Windows. These boundaries use the bundled FFmpeg process and native file copies, with cancellation, bounded lifetime, observable phases and collision-safe export. The complex extraction and transfer logic is not translated.

`android.webkit.WebView` is a narrow Java platform adapter to hidden, isolated Electron Chromium windows. The original Java player-pattern discovery, JavaScript patching, transform cache, BotGuard bootstrap, token/session ownership, cancellation/timeouts and SABR protocol remain in their source language. Helper renderers have no Node, file/content access or application IPC; they have an isolated HTTPS YouTube origin, no browser-session cookies and blocked remote networking. They report only snapshot/error callbacks. Actual native network requests still originate in the Java provider. A synthetic VM test verifies the bridge; it does not establish successful current YouTube challenges.

`CookieManager` delegates to Electron's persistent browser cookie jar, including HttpOnly cookies. Java HTTP code preserves destination-owned cookie lookup and Set-Cookie behavior. Chromium website tabs have Node integration disabled, sandbox enabled and no privileged preload API. Application IPC accepts only the trusted local top frame and validates actions, identities and paths.

`DesktopSites` connects the original Java Bilibili and FC2 contracts. The original VK detector sends bounded plans through `VkApiRequest.validate` before native Java form transport. Generic metadata and player discovery use the original detector/metadata assets and `MediaParser`/`MediaSelection`. Candidate maps belong to a navigation generation; flagged advertisements are excluded and long/short media grouping remains in the Java selection implementation.

Four bars represent overall progress segmented for presentation. No independent worker measurements are fabricated. Finite HLS may retain a valid TS container, as in the original Java transfer path; separate tracks and DASH use final container assembly. Processing is distinct from network completion.

The reference manifest records byte identity against the still-readable mobile source and each preserved engine file. No Android APK build/install occurred, and no original third-party application or raw analysis dump is in the installer.
