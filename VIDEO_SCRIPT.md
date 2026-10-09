# Video script - "A phone as a card scanner"

A script for a walkthrough video of [INSTALL_GUIDE.md](INSTALL_GUIDE.md): what to show, what
to say, what to tap. About **5 minutes**. A 60-second cut is at the end.

Each scene lists the **picture** (what is on screen), the **voice** (to read or adapt) and what
is **tapped**. Times are a guide.

It follows on from the server's video
([its script](https://github.com/SilentKernel81/scanner-server/blob/main/VIDEO_SCRIPT.md)) but
stands on its own.

## Before recording

**Have ready**

- The phone, its mount over the box, and the charging cable.
- A pile of 15-20 cards that scan well, plus a foil and one sleeved card (for the fixed area).
- For the server scenes: a scanner server running, and its address.
- For the standalone scenes: an API key in the phone's clipboard (or an Ollama server).

**Start from a phone without the app**, so the install is real. Uninstalling removes the
app's settings and cards - export the inventory first if there is one.

**Use a demo server, not your real collection**, for the server scenes: the cards scanned on
camera end up in its scanned list. The server's video script shows how to start a second,
empty one on another port.

**Recording the phone**

- Record the phone's screen with its own screen recorder (1080p), and the phone, the box and
  your hands with a second camera. Most scenes cut between the two.
- Switch on *Do not disturb* and clear the notifications; hide the status bar's personal bits
  if you care (the carrier name, other apps' icons).
- Do **not** paste the API key on camera. Paste it before recording that scene, or blur the
  field: once pasted, the app shows it masked.
- A screen recording captures the app's click and ding - keep the phone's media volume up.

**Shots to collect separately (b-roll)**

- The phone on its mount from the side; a hand dropping cards in rhythm with the click.
- Close-up of the phone's screen: the outline appearing, *Stabilizing* turning to *Ready*.
- The list under the camera filling with card names.
- The server's page, or the inventory, with the cards in it.

## The script

| # | Time | Picture | Voice | Tapped |
|---|---|---|---|---|
| 1 | 0:00-0:20 | **Cold open.** The phone on its mount, cards dropping into the box, a click each time; the phone's screen with names appearing in the list. | "This phone is scanning a pile of Magic cards - name, set, collector number, foil or not - a card every two seconds. Here's how to set it up." | - |
| 2 | 0:20-0:45 | **Two ways.** A simple split graphic: left, phone → server → collection; right, phone → AI → the phone's own inventory. | "The app works in two ways. As a camera for a scanner server on your network, which reads the cards and keeps the collection. Or on its own: the phone asks a vision AI to read each card and keeps its own list. We'll do both." | - |
| 3 | 0:45-1:30 | **Install.** Phone screen: the releases page in the browser, tap the APK under Assets, the download warning, open the file, *Allow from this source*, **Install**, **Open**, **Allow camera**. | "It isn't on Google Play - it's a file from the project's page. Download it, open it, and the first time Android asks you to allow installs from your browser. Then install, open, and let it use the camera." | The APK → **Download anyway** → open → **Settings** → *Allow from this source* → **Install** → **Open** → **Allow camera** |
| 4 | 1:30-2:00 | **Mount.** Second camera: the phone going onto its mount above the box. Phone screen: a card placed, the outline, *Stabilizing* → **Ready**. Tap rotate if the picture is sideways. | "Put the phone above the box, looking straight down, with the whole card in view. When a card is in the box it gets an outline, and when the picture is still and sharp it says Ready." | (rotate, if needed) |
| 5 | 2:00-2:40 | **Server mode.** Phone screen: the gear, switch on *Send cards to a scanner server*, type the address, **Test connection** → *Connected*. Back. | "First, as a camera for a server. In Settings, switch on 'Send cards to a scanner server', type the server's address, and test it. Connected." | gear → the switch → address → **Test connection** → back |
| 6 | 2:40-3:20 | **Scanning to the server.** Split: the box from above, and the phone's screen. **Auto** on, 6-8 cards dropped, one per click. The list: *Added on the server*. Include the foil. Then tap *Cards and review on the server*: the server's page with the cards. | "Turn on Auto and drop the cards. Wait for the click - that's the picture taken - then the next one. The server reads each card and answers: added. This one's a foil, and it knows. And here they are on the server, ready to go into the collection." | **Auto** → *Cards and review on the server* |
| 7 | 3:20-4:00 | **Standalone.** Phone screen: Settings, the switch off; *Vision AI*: provider and model (key already in, masked); *Card data (offline)* → **Download** (speed up); **Save**. | "Now on its own. Switch the server off, and choose a vision AI - you bring your own key, or point it at a model running on your network. Downloading the card data is optional: with it, cards are looked up on the phone." | gear → the switch off → provider → **Download** → **Save** |
| 8 | 4:00-4:35 | **Scanning standalone.** **Auto**, 5-6 cards. The list: names with set and number, *In inventory* and **Undo**. One goes to *Review 1*: tap it - the photo beside the suggestion - add it. Tap the count: the inventory, then the export menu (CSV, Moxfield). | "Same thing: Auto, and drop. A card whose exact printing is certain goes straight into the inventory. One the app isn't sure about waits in review, with its photo, while you keep scanning. And the inventory exports to a spreadsheet or to Moxfield." | **Auto** → **Review 1** → add → the count → export |
| 9 | 4:35-4:50 | **Fixed area.** The sleeved card: no clean outline. Tap the crop icon, drag the rectangle with the magnifier, **Done**; the card is captured. | "Sleeved cards don't give a clean outline. Draw a fixed area where the cards land instead - and from then on, don't move the phone." | crop icon → drag → **Done** |
| 10 | 4:50-5:05 | **Close.** The phone on its mount; the project's address on screen, and the caption *Built with AI: code and documentation written by Claude, directed by the author*. | "That's it. One more thing: this app - the code and the guide - was written by an AI assistant. I told it what I wanted and tested it with real cards. The guide and the download are on the project's page." | - |

## Lines for the things that go wrong on camera

| If | Say | Do |
|---|---|---|
| *App not installed* | "There's an older copy on this phone with another signature - it has to go first." | Uninstall, install again |
| The card never turns *Ready* | "Not sharp or not still enough: a bit more distance, a bit more light." | Raise the phone, switch on the light |
| *Test connection* fails | "Almost always the network: the phone has to be on the same Wi-Fi as the server." | Check Wi-Fi, the address, `http://` and `:5000` |
| A card goes to review | "It wasn't sure about this one, so it asks - that's what you want it to do." | Open the review |
| The list says *waiting to send…* | "The Wi-Fi dropped. The cards wait on the phone and go out when it's back." | Carry on |

## A 60-second cut

| Time | Picture | Voice |
|---|---|---|
| 0:00-0:08 | Cards dropping under the phone, clicks, names appearing | "A phone, scanning Magic cards at two seconds each." |
| 0:08-0:22 | The install, sped up: download, install, allow camera | "Download the app from the project's page and install it." |
| 0:22-0:32 | The phone going onto its mount; the outline and *Ready* | "Mount it over a box, looking straight down." |
| 0:32-0:45 | Settings: the server switch, the address, *Connected*; or the AI provider | "Point it at a scanner server on your network - or give it a vision AI and let it work on its own." |
| 0:45-0:55 | Auto on, cards dropping, *Added on the server* | "Turn on Auto, and drop cards on the click." |
| 0:55-1:00 | The cards in the collection; the project's address | "Guide and download in the description." |

## Notes for whoever records it

- **Say that it was built with AI** (scene 10, and the caption), and put the same line in the
  video's description.
- The numbers said out loud come from this project's own runs on a Pixel 10: 16 cards in 27 s
  as a server's camera, 13 cards in 33 s standalone with a fixed area - "about two seconds a
  card". Your phone, light and network will differ; time your own pile and say what you got.
- **Not rehearsed.** The install in scene 3 was done from a computer over USB, not by tapping
  through the browser, so the exact prompts and their order are Android's usual ones and vary
  by phone - do a dry run and adjust the voice-over. Scene 5 was done exactly as written.
  Scenes 6-9 follow what was done with an earlier build of the same code; no card has been
  scanned with the release file yet.
- Scene 8 needs a card that goes to review - one whose collector number the AI cannot read.
  Which cards do that has not been tried for this script; if none in your pile does, cut the
  review part rather than faking it.
