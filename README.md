# Snagfox for Windows — 0.1.3

Install `Snagfox-Setup.exe` from the matching **private** release of `mahostar/SnagFox-browser`. Chromium/Electron, a Temurin Java 17 runtime and FFmpeg are bundled; users do not need development tools to run the app.

## Rebuild

In the existing prepared project folder:

```powershell
cd C:\Users\Mohamed\Desktop\Snagfox-Windows
.\build.ps1
```

For a fresh clone, download `Snagfox-Build-Inputs.zip` from the matching private release first. Run these commands in the cloned repository:

```powershell
Expand-Archive -LiteralPath .\Snagfox-Build-Inputs.zip -DestinationPath . -Force
$env:JAVA_HOME = 'C:\Program Files\Eclipse Adoptium\jdk-17.0.19.10-hotspot'
.\build.ps1
```

Install **JDK 17** and **Inno Setup 6** separately before building; set `JAVA_HOME` to your actual JDK directory. Inno defaults to `C:\Program Files (x86)\Inno Setup 6\ISCC.exe`. Override either location when needed:

```powershell
.\build.ps1 -Jdk 'C:\path\to\jdk-17' -Inno 'C:\path\to\Inno Setup 6\ISCC.exe'
```

The scripts download nothing. `build-engine.ps1` compiles Java into `engine\snagfox-engine.jar`; `build.ps1` stages the app and produces `delivery\Snagfox-Setup.exe`. Use `-SkipEngine` only when the existing engine jar matches the current sources.

Git contains application and build sources, not the runtime binaries. The private build-input archive restores `vendor\electron`, `runtime`, `tools` and `build-tools`, with their license files, `THIRD_PARTY_NOTICES.md`, and `BUILD_INPUTS_SHA256.txt`. It does not contain the generated engine jar. Use inputs from the same release as the source checkout.

To prepare that archive from an already provisioned folder:

```powershell
.\prepare-build-inputs.ps1
```

This produces `delivery\Snagfox-Build-Inputs.zip` and its `.sha256` sidecar. It does not publish anything. The repository and release assets remain private.

## Implementation and source layout

The extraction/transfer engine remains Java. Electron hosts real Chromium website tabs and local HTML/CSS/JavaScript internal pages. Desktop adapters replace the Android interfaces needed by the engine; no Android emulator, NewPipe or yt-dlp is bundled.

- `engine/src/app/vidloader`, `engine/src/android`: Java engine and desktop adapters.
- `engine/assets`, `engine/lib`: browser detection/protection assets, component notices and Java library inputs.
- `main.cjs`, `preload.cjs`, `menu-preload.cjs`, `ui/`: host, validated IPC, styled menus and interface.
- `installer/Snagfox.iss`, `build*.ps1`: Windows installer and build scripts.
- `tests/`: local fixture and installed-runtime checks.
- `VERIFICATION.md`: recorded verification and its limits. Local evidence under `verification/` and reference material under `reference/` are excluded from Git.

## Run and verify

Run `delivery\app\Snagfox.exe`, or install `delivery\Snagfox-Setup.exe`. Close hides the app to the tray by default; use explicit Quit to exit. Settings control background behavior, appearance, scale, destination, concurrency, protection and optional foreground clipboard detection. Websites keep their own colors.

For a local offline self-test, use a fresh test directory:

```powershell
$exe = (Resolve-Path .\delivery\app\Snagfox.exe).Path
$testDir = Join-Path $PWD 'verification\new-test'
Start-Process $exe -ArgumentList '--self-test',"--test-dir=$testDir" -WorkingDirectory (Split-Path $exe) -WindowStyle Hidden -Wait
```

Tests create local HTTP/media fixtures and an isolated profile; normal startup does not seed sample history or downloads. Node is needed only for development drivers that explicitly invoke it. Passing local fixtures does not establish live-site coverage.

Normal browser/session and queue data stay in the user's local Snagfox profile. Completed files copy to the selected destination without overwriting existing names. File actions include Explorer, external open, Open With, save a copy, rename, delete confirmation and copying a path. Local-media selection opens a separate file in the internal player.

See `THIRD_PARTY_NOTICES.md` for bundled components and the precise source-compliance boundary before any public binary release.