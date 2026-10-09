# MTG Card Scanner - Android

Native Kotlin app (Jetpack Compose + CameraX + OpenCV) for Magic: The Gathering cards: the
phone's camera finds the card by its outline (or a fixed area), captures it once it is still,
has a vision AI read name / collector number / set code / ★• foil marker, finds the exact
printing (offline card data or the Scryfall API) and keeps an inventory with CSV / Moxfield
export and import. Uncertain cards wait in a review queue.

Ported from the Python scanner - keep both in step. The Python side is the scanner server
(Python / Flask, repository [SilentKernel81/scanner-server](https://github.com/SilentKernel81/scanner-server),
checked out as `../scanner-server`), which is also what the app talks to in client mode; the Python
paths below are in that repository. (The desktop scanner the port started from,
[SilentKernel81/scanner](https://github.com/SilentKernel81/scanner), has the same detection code and
is a separate project.)

| Android | Python |
|---|---|
| `detection/CardOutline.kt` | `object_detector.py` (`find_card_outline`, `warp_card`) |
| `detection/CardTracker.kt` | `scanner.py` (`_is_card_settled`, `_new_card_arrived`, `_outline_flicker`; fixed area: `_fixed_area_step`, same thresholds) - no focus sweep: the phone's continuous autofocus |
| `ai/CardIdentifier.kt`, `ai/Prompts.kt` | `card_identifier.py`, `prompts.py` (`BUILT_IN['mtg']`) |
| `scryfall/Scryfall.kt` | `database.py:search_card_exact` (same steps and `match` tags; offline data first, then the API) |
| `scryfall/CardDatabase.kt` | `database.py` card table: Scryfall's default cards downloaded in Settings (~80 MB, ~1.5 min), trimmed; fuzzy names like `names_match` |
| `inventory/ReviewQueue.kt`, `ui/ReviewScreen.kt` | `review.py` + the web review: uncertain cards (not confirmed, not found, nothing read, AI errors) queued with their photo while adding automatically; review with suggestion, printing search, Identify again |
| `inventory/Import.kt` | `inventory.py:import_csv` - reads this app's / the Python scanner's CSV and Moxfield's; cards looked up by set + number, then name (Scryfall `/cards/collection` online) |
| `inventory/Inventory.kt`, `inventory/Export.kt` | `inventory.py` (same table and merge key; confirmed cards added automatically like `auto_add`), the server's collection CSV (`scanner-server` `games/base.py`: `write_collection_csv` - byte-identical, `ExportParityTest`: with Card ID and Set Code, so its collection page imports each entry as the printing it is; checked 2026-10-09 with 102 entries of the emulator's inventory: all imported, every card id found in the server's card data); Moxfield |

## Client of a scanner server

The app works on its own (standalone), or as a **station** of the scanner server (the Python
project's `app.py`, repository `scanner-server`): Settings -> *Send cards to a scanner server*,
with the server's address. The phone still finds and captures the cards - camera, outline or
fixed area, stillness, new-card rules, all unchanged - but each captured card is uploaded
(`server/ScannerServer.kt` -> `POST /api/stations/<id>/captures`) and the server reads it (OCR,
then its vision AI), finds the printing and keeps the collection. The list under the camera
shows the server's answer: added (with Undo for the newest one), or in the server's review
queue. The phone's own inventory, review queue, AI settings and card data are not used and
not touched while client mode is on; *Cards and review on the server* opens the phone's page
there (`/scan/<station id>`).

- The station id is made once (`phone-xxxxxxxx`) and kept; the name defaults to the phone's model.
- A capture that can't be sent (server down, no Wi-Fi) waits on the phone and is sent when the
  server is back, under the same capture id - one card however often it is sent (see
  [Without a network](#without-a-network)).
- Tested 2026-10-09 on the emulator against the server in Docker: a capture answered in 1.1 s
  (queued for review: the emulator's camera shows no card), a capture made with the network
  off sent once when it was back, *Test connection*. On the Pixel 10 over the box: a card
  captured by hand, added on the server by its OCR in 0.6 s, and taken back with Undo; the
  phone's own inventory file unchanged. Not tested yet: auto-capture card after card in client
  mode, and a fixed-area capture (which sends a second picture for the foil check).

## Without a network

Scanning goes on without a network, in both modes. Every capture is saved in the app's storage
first (`Outbox.kt`: `files/outbox/`, the photo and - in fixed-area mode - the flat card for the
foil check) and removed once it is settled: the server has answered (client mode), or the card
is identified - added, in the review queue, or waiting for Add (standalone). Until then
`ScannerViewModel.process` tries again 2 s, 4 s, ... 30 s apart, and after the app was closed
it starts over from the outbox when the app is opened again.

- While they wait the list shows "Server not reachable - waiting to send…" / "No network -
  waiting to identify…", and a line above it counts the waiting captures. There is no card name
  and no "added" sound until the network is back - a wrong capture is only noticed then.
- Standalone: only a missing connection makes a capture wait (no route, no DNS, a timeout). An
  answer that is an error - a wrong key, a used-up quota - still sends it to the review queue
  with its photo, as before.
- A waiting capture is processed by whatever the app is set to when its turn comes: switch to
  client mode and the captures made standalone go to the server, and the other way round.
- Tested 2026-10-09 on the emulator: client mode - three captures with the network off, the app
  killed, network back, app opened: all three reached the server once and the outbox was empty;
  standalone - one capture with the network off, the app killed, network back: identified on
  the next start (to the review queue: the emulator's camera shows no card).

## Build and install

Needs Android Studio's JDK and SDK platform 37.2.

```bash
export JAVA_HOME=/opt/android-studio/jbr
./gradlew assembleDebug                      # app/build/outputs/apk/debug/app-debug.apk
~/Android/Sdk/platform-tools/adb install -r app/build/outputs/apk/debug/app-debug.apk
```

Or open this folder in Android Studio and Run. In the app: Settings -> provider, model and API
key, or the local server address (`http://<host>:11434` for Ollama; "Test / list models").

## Tests

- `./gradlew testDebugUnitTest` - AI answer parser and name similarity against the Python
  results (`app/src/test/resources/parser_reference.json`, made by `parser_reference.py`); CSV
  export against `write_csv` (`export_reference.json`, made by `export_reference.py`); CSV import
  (`ImportTest`: round trips, Moxfield's own export). The reference scripts run in the scanner
  server's checkout (`../scanner-server`) with its venv, e.g. from there:
  `venv/bin/python ../mtg-scanner-android/app/src/test/resources/export_reference.py ../mtg-scanner-android/app/src/test/resources/export_reference.json`
- `OfflineFindTest` (instrumented; card data downloaded + internet) - offline lookups against the
  API. 2026-09-26: every set + number / name + number read gave the same printing and tag; only
  name-only matches differ (Scryfall's default printing vs the newest), which go to review anyway.
- `OutlineParityTest` (instrumented) - outline detection over recorded frames; compare the
  output with `find_card_outline` on the same frames. Frames are passed in as assets:
  `./gradlew assembleDebugAndroidTest -PdebugFrames=<dir containing frames/>`, install both
  APKs, `adb shell am instrument -w -e class com.cardscanner.OutlineParityTest com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner`,
  then `adb pull /sdcard/Android/data/com.cardscanner/files/outlines.json`.
  (Measured 2026-09-26 on 400 frames of the desktop scanner's data/debug_frames - the server has
  the same `find_card_outline`: same detections as Python, corners within 0.6 px.)
- `PipelineTest` (instrumented) - card images (assets `cards/`) -> local AI -> Scryfall:
  `-e aiUrl http://host:11434 -e aiModel <model>`; writes `pipeline.json`.

## Not done yet

- Thresholds (sharpness 250, movement 1%, ...) were measured on the Pi webcam; re-measure on
  phones (live values are shown under the camera; min sharpness and still frames are in Settings).
- Tested on a Pixel 10 over the box. Auto + fixed area, 2026-09-26: 13 cards dropped in 33 s, each
  captured once, all 13 matched by set + number or name + number. Outline mode not measured in auto yet.
- Prompt editor, API keys in the Android Keystore, per-ABI APKs (debug APK is ~110 MB).
