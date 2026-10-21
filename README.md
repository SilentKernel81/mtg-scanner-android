# MTG Card Scanner - Android

A phone as a card scanner for Magic: The Gathering. Mount the phone over a box and drop cards
onto a pile: the camera finds each card by its outline (or a fixed area), captures it once it
lies still, and the card is identified down to the exact printing and finish.

> **Built with AI.** All of this app - the code, the tests and this documentation - was
> written by AI coding assistants (Claude through Claude Code, and OpenAI Codex), directed by the project's
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
- **Under the camera**, one row shows the latest capture and its status. Tap **Inventory**
  for scrollable **Scan progress**, or **Collection** for all locally saved cards. Auto scan
  keeps running while you browse. In client mode Remove also removes that captured copy from
  the server's scanned cards/review queue. Collection transfers remove the corresponding phone
  rows automatically. The brush on the camera screen removes just the latest scan and leaves
  its row blank until another capture; the brush in **Scan progress** removes
  the displayed history. Neither removes saved
  inventory cards or pending captures.

**Standalone.** A card whose exact printing is confirmed (set + number, or name + number) goes
into the phone's inventory right away, with an Undo in the list; anything uncertain goes to
the review queue with its photo - the suggested printing, what the AI read, a search to
correct it, *Identify again*. With *Add confirmed cards automatically* off, every card waits
for **Add**. These waiting cards are also saved in the review queue, so they survive restarting
the app, clearing the scan list, or scanning more than the list's 100-card limit.
The inventory can be sorted, filtered, edited, exported (CSV, Moxfield) and
imported. Settings has the AI provider, model and key (or a local server's address), the
offline card data (~80 MB, so lookups work without internet), the foil check and the capture
thresholds.

**Client mode.** Settings → *Send cards to a scanner server*, the server's address
(`http://<server>:5000`), and **Test connection**. The server only answers under names it
knows: an IP address, its own machine name (alone or with `.local`, `.lan`, `.home`, ...), or a
name in its `flask.allowed_hosts` (a reverse proxy, a Docker service name) - another name is
refused with a 403 that says so. Each capture is uploaded; the list shows
the server's answer - *Added on the server* (with capture-specific Remove) or *In the server's
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
repository as `../scanner-server`), all in its package `src/card_scanner/` - the camera's
side in `station/` (`scanner.py`, `object_detector.py`), reading, card data and the
collection in `core/` (`card_identifier.py`, `database.py`, `inventory.py`, ...), `server/` and
`games/` as written below:

| Android | Python | Used in |
|---|---|---|
| `detection/CardOutline.kt` | `object_detector.py` (`find_card_outline`, `warp_card`, `_is_whole_picture`; not ported: the gap closing and the card inside a box, `GAP_CLOSING`, `_card_inside_box`) | both modes |
| `detection/CardTracker.kt` | `scanner.py` (`_is_card_settled`, `_new_card_arrived`, `_outline_flicker`; fixed area: `_fixed_area_step`, same thresholds; the picture comparison is `_thumbnail_difference(smooth=True)` with `IMAGE_CHANGE_LIMIT` 0.35 since 2026-10-15 - `ThumbnailParityTest`, `thumbnail_reference.json`) - no focus sweep: the phone's continuous autofocus. The stillness limit is 1.5% (`STILL_LIMIT`, Python's `auto_capture.max_movement`), as the stations' since 2026-10-15; it was measured on the Pi 5's webcam, not yet on a phone's camera | both modes |
| `Outbox.kt`, `CaptureProcessor.kt`, `server/SendPolicy.kt` (when a capture stops being tried) | `station/station_client.py`: `Station.park`, `upload_verdict` (the server keeps its own record of captures being read: `pending.py`) | both modes |
| `server/ScannerServer.kt` | `server/stations_api.py`: `station_capture`, `station_capture_outcome`, `station_undo` - the phone is a station | client mode |
| `detection/CornerNet.kt`, `CornerMath.kt`, `CardFinder.kt`; `assets/corner_net.onnx` | `station/corner_net.py` (the network, run with OpenCV's dnn) and `ObjectDetector.detect` / `_plausible_neural` in `station/object_detector.py` (asked before `findCardOutline`; the outline detector answers when it is unsure) - the same file as the stations' (retrained 2026-10-15 on 42 more labelled frames; `CornerNetParityTest` and `parity/reference.json` were remade from it: Kotlin within 0.10 px of Python on four frames); trained in the server's repository (`scripts/train_corner_net.py`). Settings → Find cards with the corner network. **Written 2026-10-15; compared with the Python code on an emulator, not yet tried on a phone's camera** | both modes |
| `reading/Decide.kt`, `reading/Fingerprint.kt`, `reading/FoilNet.kt`; `assets/foil_net.onnx` | `core/reading/decide.py` (`decide_printing` - rule A2 - `decide_finish`, `plain_number`), `core/image_index.py` (`prepare`, `hash_card`, `similarity`, and `stretch_plane`, `channel_bits` which were made functions of their own for this port) and `core/foil_marker.py` (`FoilMarker.probability`: the network only - finding the set line needs the server's OCR reader, which the phone does not have) in the server's repository - the reading of a card by its picture and collector line (docs/plans/READING_REFACTOR_PLAN.md there). Passes the server's own test files (`reading_decision_cases.json`, `reading_regression_cases.json`, `reading_finish_cases.json`: copies, made by `reading_reference.py`), and the fingerprint is the same as the Python one on three pictures (`FingerprintParityTest`) - **written 2026-10-20 and compared with the Python code on an x86-64 emulator, never on a phone. NOT USED BY THE APP: nothing calls it** (the phone reads with the vision AI, or the server does). Wiring it in needs the image index on the phone (about 15 MB, built from Scryfall's pictures) and the guards the server puts around a decision by the picture alone, which are not in `Decide.kt` (see CLAUDE.md) | not wired in |
| `ui/CaptureWash.kt` (+ `ScannerScreen.kt`: `OutlineOverlay`) | `station/scanner.py`: `_mark_auto_card`, `_flash_capture`, `_wash_card` - a card found by its outline has no border, it is tinted in its state's colour (22%) and washed a stronger green (45%) fading out in 0.6 s at a capture; the fixed area keeps its rectangle and gets the wash. **Written 2026-10-15, not yet seen on a phone** | both modes |
| `FrameSidecar.kt` (+ `ScannerViewModel.kt`: `cutOutCard`, `frameOf`; `Outbox.kt`) | `station/station_client.py`: `queue_upload`; `station/scanner.py`: `_frame_snapshot`; `core/frames.py`; `server/stations_api.py`: `keep_camera_frame` - the camera frame and the outline behind a capture, sent as training data (Settings → Send camera frames; **written 2026-10-14, not yet tried on a phone**) | client mode |
| `ai/CardIdentifier.kt`, `ai/Prompts.kt` | `card_identifier.py`, `prompts.py` (`BUILT_IN['mtg']`) | standalone |
| `scryfall/Scryfall.kt` | `database.py:search_card_exact` (same steps and `match` tags; offline data first, then the Scryfall API) | standalone |
| `scryfall/CardDatabase.kt` | `database.py` card table: Scryfall's default cards downloaded in Settings (~80 MB, ~1.5 min), trimmed; fuzzy names like `names_match` | standalone |
| `inventory/ReviewQueue.kt`, `ui/ReviewScreen.kt` | `review.py` + the web review | standalone |
| `inventory/Inventory.kt` | `inventory.py` (same table and merge key; confirmed cards added automatically like `auto_add`; not ported: `inventory_batches` - which copies came in together, "Remove this batch" - the stations, trades and decks) | standalone |
| `inventory/Export.kt` | `games/base.py:write_collection_csv` - byte-identical (`ExportParityTest`); `games/mtg.py:write_moxfield` | standalone |
| `inventory/Import.kt` | `inventory.py:import_csv`, `Magic.import_rows` - the same files, looked up on Scryfall by set + number, then name | standalone |

`ScannerViewModel` owns the camera and coordinates the screen APIs. `CaptureProcessor` owns
outbox processing, bounded concurrency, retries and server operations. `CollectionRepository`
owns inventory/review handoffs, import/export and collection state; `CardDataRepository` owns
offline data management. `ScanHistory` holds the bounded display list, and `FinishPolicy` shares
the finish rules between capture and review.

Settings state and serialized background writes live in `settings/SettingsRepository.kt`.
API keys and the station token are stored together as an authenticated AES-256-GCM payload,
using a non-exportable Android Keystore key and a fresh IV for each changed payload. Ordinary
preferences remain in SharedPreferences. Existing plaintext credentials migrate on load;
ciphertext and removal of the old entries are committed together. Encryption failure preserves
the existing copy and shows an error in Settings. Unreadable credentials also show an error;
unrelated settings edits preserve the encrypted payload until the user re-enters their keys
and station token. The writer coalesces edits and flushes the latest snapshot on ViewModel
teardown. Settings snapshots redact credentials in their string representation.

The interface uses a shared light/dark theme (`ui/ScannerTheme.kt`) with a warm orange accent,
rounded feed cards, pill buttons and grouped settings panels. The Sounds panel has independent
capture, added-to-inventory and sent-to-review switches, shared by standalone and server mode.
Existing sound preferences migrate without unmuting previously disabled cues. `ui/FeedComponents.kt` provides
the shared headers, card containers and empty states. Camera preview dimensions retain the
sensor aspect ratio so detection overlays and fixed-area gestures stay aligned.

The camera screen shows the latest scan as a single-image row without movement, drift or
sharpness diagnostics. Inventory opens live **Scan progress** (the latest 100 captures) and,
in standalone mode, the full **Collection**. The inventory window keeps the camera bound so
auto-scan continues while browsing; the scroll position stays under the user's control as new
results arrive. Client mode links to the server's collection instead of exposing local data.

### A capture's way

`ScannerViewModel.capture` takes a full-resolution still, finds the card in it again and cuts
it out flat (`cutOutCard`; in fixed-area mode the photo is the area). From there
`CaptureProcessor.enqueue` / `process`:

1. saves the capture in the **outbox** (`Outbox.kt`: `files/outbox/<id>.jpg`, a second picture
   for the foil check in fixed-area mode, and a small `.json` written last);
2. hands it to whatever the app is set to at that moment - `sendOnce` (client mode) or
   `identifyOnce` (standalone);
3. if there is no connection, waits and tries again (2 s, 4 s, ... 30 s apart), holding the
   pictures only on disk meanwhile;
4. removes it from the outbox once it is settled: the server added it or queued it for review,
   or it was saved locally in the inventory or review queue (including cards waiting for Add).

Local writes record the capture id in the same transaction as the inventory/review entry.
If the app stops after that write but before removing the outbox photo, restarting does not
add another copy. Resolving a review item uses a separate inventory receipt so that handoff
can also resume safely. Database version 2 adds these receipt tables without replacing existing
cards or review photos; receipts remain after Undo or Skip to prevent replay from restoring them.

Only one standalone capture or three server captures load full-resolution photos at a time.
This limit also applies while waiting for a server's pending result. The rest remain on disk;
startup decodes small thumbnails for the scan list. A capture that cannot be saved stops Auto
and asks for another capture after freeing storage.

When the app starts, what is still in the outbox is taken up again. A capture made standalone
without a network goes to the server if the app was switched to client mode meanwhile, and
the other way round.

- **Client mode**: `ScannerServer.sendCapture` posts the photo to
  `/api/stations/<station id>/captures` with a `capture_id` (the outbox id), so a capture
  sent again after no answer is still one card on the server. The answer (`ServerOutcome`) is
  *added*, *review*, or *pending* - then `outcome()` asks again every 2 s - or *card back*: the phone
  sends `accepts=card_back`, and a card dropped face down is answered `card_back` by the server
  (its `captures.stop_at_card_back`, per camera, Settings > Cameras on the server): nothing is added, the
  row says "Card back: auto-capture stopped", the review sound plays and the phone's auto-capture
  switches off (`CaptureProcessor.onCardBack` -> `ScannerViewModel`). Since 2026-10-21; **not yet tried
  on a phone** (`ServerOutcomeTest` only).
  Each row says who read the card and, for a review, why (`ServerOutcome.statusLine`: *OCR*,
  *picture + collector line* - the server's `reading.mode: evidence`, no AI asked - or *AI (model)*), which is
  how the server's reading is told apart from the phone. The phone sends the flat warped card as
  `foil_image` (or marks `image` as that card), which is the picture the server fingerprints.
  Remove calls `DELETE /api/stations/<id>/captures/<number>/sync`: precisely that captured
  copy leaves the server's scanned cards/review queue before its phone row disappears.
  Remove all snapshots the phone's visible history, so new captures are kept. A failed removal
  leaves the row available to retry; removal requested before identification finishes is saved
  in the outbox and survives restarting. Server/station changes cannot redirect a queued upload
  or deletion to another server. Every 10 seconds the app checks settled captures with the
  synchronization endpoint, sending `sync_key` so a reused station capture number cannot target
  another card; moving them into the server's collection (with their chosen location),
  or otherwise resolving them, removes their phone rows. Collection entries are never deleted
  by a phone scan removal. This requires the matching server update. A capture the server
  cannot follow - it answers 409 (scanned before the server had synchronization, added without
  a photo, or its number belongs to another capture now) or 404 (not on record there) - can
  never be kept in step: Remove changes nothing on the server and says so, the row's button
  becomes *Remove from phone*, and a second tap takes the row off the phone only
  (`ServerRefused.cannotFollow`, `ScanResult.serverCannotFollow`). Any other failed removal (no
  connection, a wrong token) keeps the row to try again. Invalid addresses, rejected uploads and server errors keep the capture on disk
  for retry after correcting the configuration; a pending result is also kept until settled.
  **Set aside** (`SendPolicy.kt`, `SendPolicyTest`; the stations' `Station.park`, since 2026-10-16): each
  capture has its own retry loop, so none holds the others up, but one was retried every 30 s for ever. Now a
  capture the server **refuses for good** (HTTP 400 / 413) stops being tried at once, and one it answers oddly
  20 times (a 5xx or a page that is not JSON, a wrong token, a 404 from a proxy) stops after the 20th; no answer
  at all (no network, the server away) never counts. A set-aside capture stays in the outbox with its photo,
  its row says so, and the next start of the app tries it again. **Written 2026-10-16, tried by unit tests
  only - not yet on a phone.**
  The station id is made once (`phone-xxxxxxxx`) and kept; the name defaults
  to the phone's model.
- **Standalone**: only a missing connection makes a capture wait (no route, no DNS, a timeout:
  `isOffline`). An answer that is an error - a wrong key, a used-up quota - sends it to the
  review queue with its photo.

Printing search follows every online results page and includes all matching offline printings.
When downloaded data gives a weaker match than exact set + number, the app checks online for a
newer exact printing; if the network is unavailable it uses the downloaded match. Confidence tags
and the set of matches eligible for automatic Add remain the same as the Python scanner.

## Install

Download `mtg-scanner-<version>.apk` from the
[latest release](https://github.com/SilentKernel81/mtg-scanner-android/releases/latest) on the
phone and open it. Android asks once to allow installing apps from the browser or file manager
you opened it with, and Play Protect may ask whether to scan an app it has not seen before.
Needs Android 8.0 or newer and a 64-bit ARM phone (every current one). The app is not on
Google Play. A newer release installs over an older one and keeps its data.

[INSTALL_GUIDE.md](INSTALL_GUIDE.md) goes through it step by step - installing, mounting the
phone, and the first scan in each mode - and [VIDEO_SCRIPT.md](VIDEO_SCRIPT.md) is a script
for filming that.

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
- `ReadingDecideParityTest`, `FingerprintMathTest`, `FoilMathTest` (JVM) - the reading port (`reading/`, **not used by the app yet**):
  the decision against the server's three test files (22 hand-written cases, 135 captured from real pictures,
  20 for the finish), the fingerprint's arithmetic (the percentile stretch, the median and the packing of bits) and the
  foil network's (a crop's normalisation, the softmax) against vectors made by the Python code. `reading_reference.py` makes
  every reference file and the copies of the server's test files; run it from the server's checkout:
  `venv/bin/python ../mtg-scanner-android/app/src/test/resources/reading_reference.py`.
- `FingerprintParityTest`, `FoilNetParityTest` (instrumented, **on an emulator, never the user's phone**) - the OpenCV
  steps and the network on the emulator: 2026-10-20, `Pixel_Phone` (x86-64): the fingerprint of two synthetic cards and one real
  capture (PNGs in `androidTest/assets/parity`) differs from the Python one in **0 of 768 bits**, and the foil network is
  within 5e-8 of the Python probability on 16 crops. Not tried on a phone (another CPU's DCT may put a frequency on the
  other side of the median: the test allows 8 bits). Run with
  `adb -s <emulator serial> shell am instrument -w -e class com.cardscanner.FingerprintParityTest,com.cardscanner.FoilNetParityTest com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner`.
- `ScryfallSearchTest` and the address cases in `ServerOutcomeTest` also cover paginated printing
  search and malformed server addresses, using intercepted HTTP rather than live services.
- `ReliabilityTest` (instrumented) uses isolated temporary databases, synthetic photos and
  intercepted HTTP. It covers manual Add beyond the scan-list limit and across restarts,
  repeated Add/Undo, interrupted inventory/review commits, version-1 database upgrades,
  bounded server polling, cancellation, outbox thumbnails and offline search/deletion. Run only
  that class on an emulator after installing both APKs:
  `adb shell am instrument -w -e class com.cardscanner.ReliabilityTest com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner`.
  It does not capture from the camera or call real AI, Scryfall or scanner servers.
- `CredentialStorageTest` (instrumented) uses unique preferences and Android Keystore aliases
  with synthetic credentials. It covers encryption, plaintext migration and migration failure,
  tampering, key loss, credential removal, randomized IVs and final settings writes on cancellation.
  Run it alongside the isolated reliability suite with
  `adb shell am instrument -w -e class com.cardscanner.ReliabilityTest,com.cardscanner.CredentialStorageTest com.cardscanner.test/androidx.test.runner.AndroidJUnitRunner`.
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
| The release build | the file downloaded from the releases page, on a Pixel 10, 2026-10-09 | Installs, starts and shows the camera screen ("No card"). No card has been scanned with it yet |

Not done yet:

- The thresholds (sharpness 250, movement 1.5%, ...) were measured with the Python scanner's USB
  webcam; re-measure on phones (minimum sharpness and still frames are in Settings).
- Standalone auto mode with outline detection has not been measured; nor a long offline pile
  on a real phone, nor a fixed-area capture in client mode (it sends a second picture).
- The station token has not been tried from the app.
- The server's Host check (2026-10-14) has not been tried from the phone with a name the server
  refuses: the server's 403 now carries its reason as `message` (the field `ServerRefused`
  shows), but only a server from `27b0c55` on does; an older one shows "HTTP 403".
- Prompt editor, per-ABI APKs.

### After the server's changes of 2026-10-14

The server moved to one code base with the standalone program, and gained batches, Host and
Origin checks, one transaction per edit and a restore that holds the locks. Checked from this
side, against scanner-server `27b0c55`, with no phone and no Gradle:

- The five addresses `ScannerServer.kt` calls (`GET /api/stations`, `POST .../captures`,
  `GET .../captures/<n>`, `GET` and `DELETE .../captures/<n>/sync`, `POST .../undo`) are in the
  server's recorded route list with the same methods.
- `export_reference.py` and `parser_reference.py`, run in the server's checkout, give files
  byte-identical to the committed `export_reference.json` and `parser_reference.json`: the
  collection CSV and the answer parser have not drifted.
- Every Python file named in the table above is where it says (`src/card_scanner/...`).
- The server's 334 tests pass.

Not run: the Android JVM tests, the reliability tests, the emulator-to-server contract test,
and a phone against the new Host check.

## How this project was built

This app was developed entirely with AI. The code, the tests and this documentation were
written by AI coding assistants. Initial development used Claude, by Anthropic, through Claude
Code; the reliability review and subsequent fixes used OpenAI Codex. The initial commit history
records Claude's contributions. The [scanner server](https://github.com/SilentKernel81/scanner-server)
was also developed with AI.

The project's author decided what to build and how it should behave, and ran it on a real
phone over a box of real cards; *What has been tried* is what came of those runs. It has been run,
not audited by a human: the code review and fixes were also performed by AI. Automated tests
cover compatibility with the server and the persistence/concurrency paths listed above.

## License

[PolyForm Noncommercial 1.0.0](LICENSE) - free for personal use, hobby projects, study, research
and non-profit use. You may change it for yourself and share it with others under the same terms.
You may **not** sell it or use it commercially without the author's permission. The source is
public (*source-available*); it is not under an OSI open-source license. Versions published
before 2026-10-19 were released under the MIT license, and copies of those stay under it.

### Capture synchronization validation (2026-10-12)

Validated against scanner-server commit `25be00d`: all 182 server tests, 16 Android JVM tests,
17 Android reliability tests, and an opt-in emulator-to-server HTTP contract test passed.
The HTTP test used temporary server databases and synthetic cards: removing one duplicate,
repeating deletion, rejecting a mismatched capture identity, keeping another station's card,
and moving a scan into a collection/location before attempting deletion again. No production
inventory was changed. `ServerSyncIntegrationTest` runs only when the instrumentation argument
`serverValidationUrl` points to the disposable fixture server; ordinary test runs skip it.

Added afterwards (same day): the phone-only removal of a capture the server cannot follow, and
the detector's rule that the picture's own border is not a card. 19 reliability tests pass on
the emulator (two new: a 409 leaves the row until the second Remove; a 401 keeps it), and the
JVM tests. The detector rule was measured on the server's side only (its
docs/PROGRAM_DOCUMENTATION.md, Card detection, step 8) - `OutlineParityTest` was not run for
it. On the Pixel 10 against the real server (same day): one card captured three times, the
middle row removed - the server lost exactly that capture's copy; the other two cleared on the
server - their rows left the phone by themselves. Not tried there: a review item, the move
into the collection, and a capture the server cannot follow.
