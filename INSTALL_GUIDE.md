# Installing the app - step by step

From nothing to scanning cards with a phone. About 10 minutes.

The app works in two ways, and you choose in its Settings:

- **As a camera of a scanner server** - the phone takes the pictures, a
  [scanner server](https://github.com/SilentKernel81/scanner-server) on your network reads the
  cards and keeps the collection. Follow Parts 1, 2 and 3A.
- **On its own (standalone)** - the phone asks a vision AI to read each card and keeps its own
  inventory. Follow Parts 1, 2 and 3B.

You can switch between the two at any time.

This app, including this guide, was written by an AI coding assistant (Claude) directed by its
author - see the [README](README.md#how-this-project-was-built). The last section says how the
steps below were checked.

## What you need

| | |
|---|---|
| **A phone** | Android 8.0 or newer, 64-bit ARM (every current phone). About 150 MB free |
| **A way to hold it** | The phone must look straight down at the cards and stay put: a phone arm, a tripod with a phone clamp, or a shelf with a hole. 20-30 cm above the cards |
| **A box or a mat** | Plain and light, a little larger than a card - the card's dark border must stand out |
| **Light** | Even room light. The app can switch on the phone's light, which helps in a dim room but glares on foils |
| **For a server** | A scanner server running on the same Wi-Fi, and its address (`http://<address>:5000`) - its [Docker guide](https://github.com/SilentKernel81/scanner-server/blob/main/DOCKER_GUIDE.md) sets one up |
| **For standalone** | An API key for Google Gemini, OpenAI, Anthropic or OpenRouter, or an [Ollama](https://ollama.com) server with a vision model on your network |

## Part 1 - Install

The app is not on Google Play; it is a file you download.

**1.** On the phone, open
**https://github.com/SilentKernel81/mtg-scanner-android/releases/latest** in the browser.

**2.** Under *Assets*, tap **`mtg-scanner-<version>.apk`** (about 41 MB). The browser may warn
that this kind of file can be harmful - that is its standard text for any app outside the
store. Tap **Download anyway**.

**3.** Open the downloaded file (the browser's "Open" link, or the Files app → Downloads).

**4.** The first time, Android says the browser (or Files) is not allowed to install apps. Tap
**Settings**, switch on **Allow from this source**, and go back.

**5.** Tap **Install**. Play Protect may offer to scan the app first, since it has not seen it
before - let it, then continue.

**6.** Tap **Open**. The app asks for the camera: **Allow camera**, then *While using the app*.

You now see the camera picture with **No card** at the top left, and a row of controls under
it: **Auto**, **Capture**, and four icons - fixed area, rotate, light, settings.

To update later, download a newer release the same way and install it over this one; settings
and cards are kept.

## Part 2 - Mount the phone

**1.** Fix the phone above the box, camera pointing straight down, so that a card on the pile
fills roughly a third of the picture's width and the **whole card** is in view.

**2.** If the picture is sideways, tap the **rotate** icon until the card stands upright.

**3.** Put a card in the box. It gets an outline and the status changes from *No card* to
*Stabilizing* and then **Ready**. If it never becomes Ready, see
[If something goes wrong](#if-something-goes-wrong).

**4.** Keep the phone plugged in for a long session: the camera and the screen stay on.

## Part 3A - As a camera of a scanner server

**1.** Tap the **settings** icon (the gear).

**2.** Switch on **Send cards to a scanner server**. Three fields appear.

**3.** In **Server address** type your server's address, for example
`http://192.168.1.20:5000`.

**4.** **This phone's name on the server** is what the server will call this camera; the
phone's model is filled in. Leave **Station token** empty unless your server was given one.

**5.** Tap **Test connection**. It answers **Connected: N stations**. An error instead means
the address is wrong, the server is off, or the phone is on another network (mobile data, a
guest Wi-Fi).

**6.** Go back (the arrow at the top left). The screen now has a link, **Cards and review on
the server**.

**7.** Switch on **Auto** and drop cards onto the pile, one at a time. **Wait for the click
before dropping the next one** - it means the picture is taken. Each card appears in the list
under the camera, and a moment later with the server's answer:

- **Added on the server** - with **Undo** on the newest one;
- **In the server's review queue** - the server was not sure; you decide there.

**8.** Tap **Cards and review on the server** to open the phone's page on the server: the
scanned cards, the review queue, and from there the collection.

If the Wi-Fi drops, keep scanning: the captures wait on the phone (*waiting to send…*) and go
out when the connection is back, even if the app was closed in between.

## Part 3B - On its own (standalone)

**1.** Tap the **settings** icon. Leave *Send cards to a scanner server* off.

**2.** Under **Vision AI**, choose the **Provider** and the **Model**, and paste your **API
key**. For an Ollama server choose **Local server (Ollama)** as provider, enter its address
(`http://<address>:11434`) and tap **Test / list models**.

**3.** Optional - under **Card data (offline)** tap **Download** (about 80 MB, a minute or
two; keep the app open). Cards are then looked up on the phone instead of on Scryfall's
website, which is faster and works without internet.

**4.** Under **Scanning**, leave **Add confirmed cards automatically** on: cards matched by
set + number go straight into the inventory, uncertain ones into a review queue, and scanning
goes on. Tap **Save**.

**5.** Switch on **Auto** and drop cards onto the pile, one per click. Each one appears in the
list with its name, set and number, and either **In inventory** (with **Undo**) or **In the
review queue**.

**6.** Tap **Review N** to go through the uncertain ones: the photo beside the suggested
printing - add it, search for the right one, or have it identified again.

**7.** Tap the card count to open the inventory: sort, filter, edit, and export as **CSV** or
for **Moxfield**. The CSV is the scanner server's collection format, should you move to a
server later.

What it costs: with Gemini Flash-Lite, the default, about $0.80 per 1,000 cards (measured on
2026-09-26; prices change). The foil check is a second, small request per card and can be
switched off.

## Sleeved and borderless cards

The outline of a sleeved or borderless card is unreliable. Tap the **fixed area** icon (the
crop mark) and drag a rectangle around the spot where the cards land - a magnifier helps with
the corners - then **Done**. The app now judges cards by the picture inside that rectangle.
The phone must not move afterwards; **Redraw area** if it did.

## If something goes wrong

| What you see | What to do |
|---|---|
| The download does not start, or the file does not open | Use Chrome or Firefox, and open the file from the Files app → Downloads |
| **App not installed** | Usually an older copy built on a computer is on the phone (it has another signature): uninstall it first - its settings and cards go with it, so export the inventory before |
| **App not installed as app isn't compatible with your phone** | The phone is 32-bit or older than Android 8.0 |
| The status stays on **No card** | The whole card must be in view on a background that contrasts with its border. For sleeves, use the fixed area |
| It stays on **Stabilizing** or never says **Ready** | The picture is not sharp enough or the phone moves. Raise the phone a little (too close and it cannot focus), add light, and steady the mount. *Minimum sharpness* in Settings can be lowered |
| **Test connection** fails | Open the server's address in the phone's browser. If that fails too, it is the network: same Wi-Fi, no mobile data, `http://` not `https://`, port `:5000` |
| Cards stay on **waiting to send…** | The server is not reachable right now; they are sent when it is |
| Many cards go to review | The small print at the bottom of the card is too soft to read: more light, a steadier phone, or a little more distance |
| Standalone: every card goes to review with an error | The key is wrong or its quota is used up - the error text in the review entry says which |

## How this guide was checked

On 2026-10-09 the release file was downloaded from the releases page and installed on a Pixel
10 (Android 17) from a computer, over USB - **not** by tapping through the browser, so the
exact wording of Android's prompts in Part 1 (steps 2-5) is from how Android handles such
installs in general and differs between phones and versions. Part 3A, steps 1-6, were done on
that phone exactly as written, ending in "Connected: 3 stations". Scanning in both modes was
done on the same phone with an earlier build of the same code (16 cards in 27 s as a server's
camera; 13 cards in 33 s standalone with a fixed area), not yet with this release file.
