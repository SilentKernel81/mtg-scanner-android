# MTG Card Scanner - Android

A phone as a card scanner for Magic: The Gathering. Mount the phone over a box and drop cards
onto a pile: the camera finds each card by its outline (or a fixed area), captures it once it
lies still, and the card is identified down to the exact printing and finish.

> **Built with AI.** All of this app - the code, the tests and this documentation - was
> written by an AI coding assistant (Claude, through Claude Code), directed by the project's
> author. See [How this project was built](#how-this-project-was-built).

Native Kotlin (Jetpack Compose, CameraX, OpenCV). It works in two ways, switched in Settings:

| | Standalone | Client of a scanner server |
|---|---|---|
| Finds and captures the card | the phone | the phone |
| Reads the card | a vision AI the phone asks (Gemini, OpenAI, Anthropic, OpenRouter, a local Ollama) | the server: OCR first, then its vision AI |
| Finds the printing | the phone: offline card data, or the Scryfall API | the server |
| Keeps the cards | the phone's own inventory and review queue; CSV / Moxfield export and import | the server's scanned cards, review queue and collection |
| Needs | an AI key or a local model; internet for Scryfall unless the card data is downloaded | the server's address |

The server is [scanner-server](https://github.com/SilentKernel81/scanner-server): one machine
that reads the cards of any number of cameras - webcams on small computers, and phones running
this app - and keeps one collection. The phone is one of its *stations*.

## Using it

- **Scan**: the camera view shows the detected card outlined and a status - *Stabilizing*,
  *Ready*, *Captured - drop the next card*. Turn on **Auto** and drop cards onto the pile, or
  tap **Capture** for one. A click sounds at each capture: drop the next card.
- **Fixed area** (the crop button) is for sleeved or borderless cards, whose outline is
  unreliable: draw a rectangle around where the cards land, and cards are judged by the image
  inside it. The phone must not move afterwards.
- **Rotate** turns the image when the phone is mounted sideways; the light button switches the
  torch.
- **The list under the camera** shows each capture and what became of it.

**Standalone.** A card whose exact printing is confirmed (set + number, or name + number) goes
into the phone's inventory right away, with an Undo in the list; anything uncertain goes to
the review queue with its photo - the suggested printing, what the AI read, a search to
correct it, *Identify again*. With *Add confirmed cards automatically* off, every card waits
for **Add**. The inventory can be sorted, filtered, edited, exported (CSV, Moxfield) and
imported. Settings has the AI provider, model and key (or a local server's address), the
offline card data (~80 MB, so lookups work without internet), the foil check and the capture
thresholds.

**Client mode.** Settings → *Send cards to a scanner server*, the server's address
(`http://<server>:5000`), and **Test connection**. Each capture is uploaded; the list shows
the server's answer - *Added on the server* (with Undo for the newest card) or *In the server's
review queue*. *Cards and review on the server* opens the phone's page there. The phone's own
inventory, review queue, AI settings and card data are not used and not touched while client
mode is on.

**Without a network** scanning goes on in both modes. Every capture is saved on the phone
first and stays until it is settled; it is tried again when the network is back, also after
the app was closed. The list says *waiting to send…* / *waiting to identify…* and a line above
it counts the waiting captures. There is no card name and no "added" sound until then, so a
wrong capture is only noticed later.

**Moving a standalone inventory to the server**: export **CSV** on the phone and import the
file on the server's collection page. The file is the server's own format, so every entry
arrives as the printing it is.

## How it is built

The detection and capture rules, the AI prompts and answer parser, the printing match and the
CSV format are ports of the server's Python code - keep both in step. The Python paths are in
[scanner-server](https://github.com/SilentKernel81/scanner-server) (checked out beside this
repository as `../scanner-server`):

| Android | Python | Used in |
|---|---|---|
| `detection/CardOutline.kt` | `object_detector.py` (`find_card_outline`, `warp_card`) | both modes |
| `detection/CardTracker.kt` | `scanner.py` (`_is_card_settled`, `_new_card_arrived`, `_outline_flicker`; fixed area: `_fixed_area_step`, same thresholds) - no focus sweep: the phone's continuous autofocus | both modes |
| `Outbox.kt`, `ScannerViewModel.process` | - (the server keeps its own record of captures being read: `pending.py`) | both modes |
| `server/ScannerServer.kt` | `app.py`: `station_capture`, `station_capture_outcome`, `station_undo` - the phone is a station | client mode |
| `ai/CardIdentifier.kt`, `ai/Prompts.kt` | `card_identifier.py`, `prompts.py` (`BUILT_IN['mtg']`) | standalone |
| `scryfall/Scryfall.kt` | `database.py:search_card_exact` (same steps and `match` tags; offline data first, then the Scryfall API) | standalone |
| `scryfall/CardDatabase.kt` | `database.py` card table: Scryfall's default cards downloaded in Settings (~80 MB, ~1.5 min), trimmed; fuzzy names like `names_match` | standalone |
| `inventory/ReviewQueue.kt`, `ui/ReviewScreen.kt` | `review.py` + the web review | standalone |
| `inventory/Inventory.kt` | `inventory.py` (same table and merge key; confirmed cards added automatically like `auto_add`) | standalone |
| `inventory/Export.kt` | `games/base.py:write_collection_csv` - byte-identical (`ExportParityTest`); `games/mtg.py:write_moxfield` | standalone |
| `inventory/Import.kt` | `inventory.py:import_csv`, `Magic.import_rows` - the same files, looked up on Scryfall by set + number, then name | standalone |

### A capture's way

`ScannerViewModel.capture` takes a full-resolution still, finds the card in it again and cuts
it out flat (`cutOutCard`; in fixed-area mode the photo is the area). From there
`ScannerViewModel.process`:

1. saves the capture in the **outbox** (`Outbox.kt`: `files/outbox/<id>.jpg`, a second picture
   for the foil check in fixed-area mode, and a small `.json` written last);
2. hands it to whatever the app is set to at that moment - `sendOnce` (client mode) or
   `identifyOnce` (standalone);
3. if there is no connection, waits and tries again (2 s, 4 s, ... 30 s apart), holding the
   pictures only on disk meanwhile;
4. removes it from the outbox once it is settled: the server answered, or the card was
   identified (added, in the review queue, or waiting for Add).

When the app starts, what is still in the outbox is taken up again. A capture made standalone
without a network goes to the server if the app was switched to client mode meanwhile, and
the other way round.

- **Client mode**: `ScannerServer.sendCapture` posts the photo to
  `/api/stations/<station id>/captures` with a `capture_id` (the outbox id), so a capture
  sent again after no answer is still one card on the server. The answer (`ServerOutcome`) is
  *added*, *review*, or *pending* - then `outcome()` asks again every 2 s. Undo calls
  `/api/stations/<id>/undo`; the server keeps one Undo per station, so only the newest card
  has the button. The station id is made once (`phone-xxxxxxxx`) and kept; the name defaults
  to the phone's model.
- **Standalone**: only a missing connection makes a capture wait (no route, no DNS, a timeout:
  `isOffline`). An answer that is an error - a wrong key, a used-up quota - sends it to the
  review queue with its photo.

## Install

Download `mtg-scanner-<version>.apk` from the
[latest release](https://github.com/SilentKernel81/mtg-scanner-android/releases/latest) on the
phone and open it. Android asks once to allow installing apps from the browser or file manager
you opened it with, and Play Protect may ask whether to scan an app it has not seen before.
Needs Android 8.0 or newer and a 64-bit ARM phone (every current one). The app is not on
Google Play. A newer release installs over an older one and keeps its data.

## Build it yourself

Needs Android Studio's JDK and SDK platform 37.2.

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk (~110 MB: OpenCV)
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open this folder in Android Studio and Run. `install -r` keeps the app's data (settings,
inventory, the outbox). A build of your own is signed with your machine's debug key, so it
cannot be installed over a downloaded release (or the other way round) without uninstalling
first, which removes the app's data.

## Tests

- `./gradlew testDebugUnitTest` - the AI answer parser and name similarity against the Python
  results (`ParserParityTest`, `parser_reference.json`); the CSV export byte for byte against
  the server's `write_collection_csv` (`ExportParityTest`, `export_reference.json`); CSV import
  (`ImportTest`: round trips, Moxfield's own export); the server's answers in client mode
  (`ServerOutcomeTest`). The reference files are made by the `*_reference.py` scripts in
  `app/src/test/resources`, run in the server's checkout with its venv, e.g. from there:
  `venv/bin/python ../mtg-scanner-android/app/src/test/resources/export_reference.py ../mtg-scanner-android/app/src/test/resources/export_reference.json`
  (`parser_reference.py` prints its JSON instead).
- `OfflineFindTest` (instrumented; card data downloaded + internet) - offline lookups against the
  API. 2026-09-26: every set + number / name + number read gave the same printing and tag; only
  name-only matches differ (Scryfall's default printing vs the newest), which go to review anyway.
- `OutlineParityTest` (instrumented) - outline detection over recorded frames; compare the
  output with `find_card_outline` on the same frames. Frames are passed in as assets:
  `./gradlew assembleDebugAndroidTest -PdebugFrames=<dir containing frames/>`, install both
  APKs, `adb shell am instrument -w -e class com.cardscanner.OutlineParityTest com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner`,
  then `adb pull /sdcard/Android/data/com.cardscanner/files/outlines.json`.
  (Measured 2026-09-26 on 400 recorded frames: same detections as Python, corners within 0.6 px.)
- `PipelineTest` (instrumented) - card images (assets `cards/`) -> local AI -> Scryfall:
  `-e aiUrl http://host:11434 -e aiModel <model>`; writes `pipeline.json`.

## What has been tried

| What | Where | Result |
|---|---|---|
| Standalone, auto + fixed area | Pixel 10 over the box, 2026-09-26 | 13 cards dropped in 33 s, each captured once, all 13 matched by set + number or name + number |
| Client mode, one capture and Undo | Pixel 10, 2026-10-09 | Added on the server by its OCR in 0.6 s; Undo removed it; the phone's inventory file unchanged |
| Client mode, auto, outline | Pixel 10, 2026-10-09 | 16 cards in 27 s, all read by the server's OCR and added. The pile was thought to hold one more; whether a drop was missed was not established |
| Client mode without a network, app killed | emulator, 2026-10-09 | Three captures reached the server once each after the network and the app were back |
| Standalone without a network, app killed | emulator, 2026-10-09 | The capture was identified on the next start |
| CSV export → server import | 102 entries of the emulator's inventory, 2026-10-09 | All imported, every card id found in the server's card data |
| The release build | emulator (an x86_64 copy of it), 2026-10-09 | Starts and shows the camera screen. The downloadable arm64 file itself has not been installed on a phone |

Not done yet:

- The thresholds (sharpness 250, movement 1%, ...) were measured with the Python scanner's USB
  webcam; re-measure on phones (live values are shown under the camera; minimum sharpness and
  still frames are in Settings).
- Standalone auto mode with outline detection has not been measured; nor a long offline pile
  on a real phone, nor a fixed-area capture in client mode (it sends a second picture).
- The station token has not been tried from the app.
- Prompt editor, API keys in the Android Keystore, per-ABI APKs.

## How this project was built

This app was developed entirely with AI. The code, the tests and this documentation were
written by an AI coding assistant - Claude, by Anthropic, working through Claude Code - as the
commit history records (every commit carries a `Co-Authored-By: Claude` line). The same goes
for the [scanner server](https://github.com/SilentKernel81/scanner-server) it works with.

The project's author decided what to build and how it should behave, and ran it on a real
phone over a box of real cards; *What has been tried* is what came of those runs. It has been run,
not audited: no one has reviewed the code independently, and the automated tests cover the
parts that must match the server (the answer parser, the CSV format, the server's answers).

## License

[MIT](LICENSE) - free to use, modify and share, including commercially, as long as the
copyright notice stays with it. No warranty.
