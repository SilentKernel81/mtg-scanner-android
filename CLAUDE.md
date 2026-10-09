# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

Android app (Kotlin, Jetpack Compose, CameraX, OpenCV) that scans Magic: The Gathering cards
dropped into a box: the camera finds each card by its outline or a fixed area, captures it once
it is still, a vision AI (Gemini / OpenAI / Anthropic / OpenRouter / local Ollama) reads name,
collector number, set code and the ★/• foil marker, the printing is matched (offline card data
or the Scryfall API) and the card goes into an inventory - or, if uncertain, into a review queue.
Magic only (no Pokémon).

It is a port of the Python scanner. Its Python side is the scanner server, repository
[SilentKernel81/scanner-server](https://github.com/SilentKernel81/scanner-server), checked out
beside this one (`../scanner-server`) - also the server this app sends its captures to in client
mode. **README.md maps each Kotlin file to the Python code it ports** - when changing detection,
auto-capture, prompts / answer parsing, matching or the CSV formats, check the Python side and
keep both in step (the thresholds were measured there; see its PROGRAM_DOCUMENTATION.md).
The desktop scanner the port started from ([SilentKernel81/scanner](https://github.com/SilentKernel81/scanner),
`../scanner`) is a separate project with the same detection code; it is not changed from here.

## Common Commands

```bash
export JAVA_HOME=/opt/android-studio/jbr     # Android Studio's JDK (Java 25); there is no system java
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk (~110 MB: OpenCV)
./gradlew testDebugUnitTest                  # JVM tests (parity with the Python code)
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb logcat -s Scanner:I     # captures, identifications, review queue
```

Toolchain: AGP 9.4 (built-in Kotlin, no `kotlin-android` plugin), Kotlin 2.4, Gradle 9.8 wrapper,
compileSdk 37.2 (`release(37) { minorApiLevel = 2 }` - current AndroidX / OkHttp require 37),
minSdk 26, ABIs arm64-v8a + x86_64. `local.properties` (sdk.dir) is not in git. Don't add
`jvmToolchain(17)`: it would try to download a JDK 17.

## Testing

- **Unit tests** (`app/src/test`): answer parser and name similarity (`ParserParityTest`), CSV
  export byte-identical to the server's `write_collection_csv` (`ExportParityTest`), CSV import
  (`ImportTest`), the server's answers in client mode (`ServerOutcomeTest`). Reference JSON files
  are made by the `*_reference.py` scripts in `app/src/test/resources`, run from
  `../scanner-server` with its venv (`parser_reference.py` prints its JSON, `export_reference.py`
  writes the file named on its command line).
- **Instrumented tests** (`app/src/androidTest`): run them with `adb shell am instrument -w -e class
  com.cardscanner.<Test> com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner` after
  installing both APKs (`assembleDebug assembleDebugAndroidTest`) - **not** `connectedDebugAndroidTest`,
  which uninstalls the app afterwards and wipes its data (and the files the tests write). Results
  go to `/sdcard/Android/data/com.cardscanner/files/`. `OutlineParityTest` takes recorded frames
  as assets (`-PdebugFrames=<dir with frames/>`), `PipelineTest` a local AI (`-e aiUrl -e aiModel`),
  `OfflineFindTest` downloaded card data + internet. Details in README.md.
- **Real device**: the user's Pixel 10 is mounted over the scanning box (USB debugging). Check
  changes there with `adb install -r` (keeps the app's data), `adb exec-out screencap -p` and
  `adb shell input tap` (screen 1080x2424). The inventory on it is the user's real collection:
  **never import test CSVs or clear it there** - use the emulator for that.
- **Emulator** (`Pixel_Phone`, `-camera-back webcam1` uses the box webcam): fine for UI and flows,
  not for reading quality - its camera is 640x480, too low for collector numbers.

## Where Things Live

| Area | Code |
|---|---|
| Card outline, warp (port of `object_detector.py`) | `detection/CardOutline.kt`: `findCardOutline`, `warpCard` |
| Stillness, auto-capture, new card, fixed area (port of `scanner.py`) | `detection/CardTracker.kt`: `process`, `processArea`, `isCardSettled`, `newCardArrived` |
| Camera, capture, identification flow, inventory / review / import / card data actions | `ScannerViewModel.kt` (`analyze`, `capture`, `cutOutCard`, `identify`, `queueForReview`, `importCsv`, `downloadCardData`) |
| AI providers, answer parser, foil check, token use | `ai/CardIdentifier.kt`; prompts (= `prompts.py` BUILT_IN mtg) in `ai/Prompts.kt` |
| Printing match (`search_card_exact` steps, `match` tags, `CONFIRMED`) | `scryfall/Scryfall.kt` (offline first, then the API) |
| Offline card data (Scryfall default cards → SQLite) | `scryfall/CardDatabase.kt` |
| Inventory (SQLite, Python's table + merge key), exports, import | `inventory/Inventory.kt`, `inventory/Export.kt`, `inventory/Import.kt` |
| Review queue (photo + reading + suggestion) | `inventory/ReviewQueue.kt`, `ui/ReviewScreen.kt`, printing search `ui/PrintingSearch.kt` |
| Screens | `ui/ScannerScreen.kt` (camera, overlay, area drawing with loupe, scan list), `ui/InventoryScreen.kt`, `ui/SettingsScreen.kt` |
| Captures kept until they are settled (no network, app closed) | `Outbox.kt`; `ScannerViewModel.kt`: `process` (the retry loop, both modes), `identifyOnce`, `sendOnce`, `isOffline`, the `init` block that resumes the outbox |
| Settings (SharedPreferences, saved on every change) | `AppSettings.kt` |
| Client mode: captures go to a scanner server instead of the phone's own AI / Scryfall / inventory | `server/ScannerServer.kt` (`sendCapture`, `outcome`, `undo`, `test`, `ServerOutcome.parse`); `ScannerViewModel.kt`: `sendToServer`, `serverAnswered`, `undoOnServer`; `ui/ScannerScreen.kt`: `ServerScanRow`; `ui/SettingsScreen.kt`: `ServerSection`; the server side is `app.py: station_capture` in `../scanner-server` |
| Sound effects (the web UI's beeps) | `Sounds.kt` |

## Conventions and Pitfalls

- **Match confidence**: only `Scryfall.CONFIRMED` matches (`set_number`, `name_number`,
  `name_set_digit` - same as the Python `CONFIRMED_MATCHES`) are added automatically; everything
  else goes to the review queue while `autoAdd` is on. Keep new lookup paths tagged.
- **Frames**: analysis frames are ~1280x960 (4:3), turned upright plus the user's extra rotation;
  stills are ~3264x2448 in the same 4:3 view, so outlines / areas only need scaling and the still
  is searched again for the exact edges (`cutOutCard`). In fixed-area mode the photo is the area.
- **Fixed area** assumes the phone doesn't move (it compares a fixed spot of the image).
- **Preview rotation** needs `PreviewView.ImplementationMode.COMPATIBLE` (TextureView); a
  SurfaceView ignores view rotation.
- **Keyed LazyColumns** keep the previously first item in view when items are inserted above it
  or the order changes - new items end up hidden above. Scroll to the top on new scans / sort /
  filter changes (see `ScannerScreen`, `InventoryScreen`).
- **SQLite on minSdk 26 is 3.18**: no `ON CONFLICT ... DO UPDATE` (upsert by hand in a transaction);
  bind mixed-type arguments as `arrayOf<Any?>(...)`.
- **Scryfall** requires a User-Agent and Accept header - also for card images: the Coil image
  loader in `MainActivity` sets `Scryfall.USER_AGENT` (OkHttp's default gets HTTP 400). Keep
  50-100 ms between bulk API requests.
- **AI costs**: Gemini Flash thinks by default (~90% of its output tokens); Flash-Lite is the
  default (same reads on a 10-card test, ~$0.79 per 1000 cards). OpenRouter's `:free` vision
  models were rate-limited upstream when tested (2026-09-26) - not dependable. Ollama requests
  send `think: false` and `keep_alive`.
- **Never show or log API keys** (Settings masks them; read them only into shell variables).
- **UI texts are in English**; the user talks in Portuguese.
- Commit and push only when the user asks.
