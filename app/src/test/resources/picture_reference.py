"""
Makes the reference of the phone's reading by the picture (reading/ImageIndex.kt, reading/PictureReader.kt; the server's
docs/plans/ANDROID_PICTURE_STANDALONE_PLAN.md, step 1) from the scanner server's own code: what PrintingCheck.live_read decides with NO OCR reader - the
server's "OCR off" configuration, the only one a phone without OCR can be - for each of the owner's 41 Pixel captures, against the server checkout's own
picture index. Run it from the server's checkout with its venv, as reading_reference.py:

    cd ../scanner-server && venv/bin/python ../mtg-scanner-android/app/src/test/resources/picture_reference.py

What it writes, all into app/src/androidTest/assets/picture/ - IGNORED BY GIT (about 40 MB: the index is data, never in a repository, as on the server):
  index/manifest.json, index/hashes-*.bin, index/ids-*.txt, index/printings.tsv.gz
        a COPY of the server checkout's data/image_index (its current generation) and the printings table a server would serve beside it
        (image_index.printings_table of that checkout's card data) - the same files GET /api/image_index/download hands a phone
  pictures/*.png
        the Pixel captures (~/.cache/mtg-image-match/truth/rescan_cards/), halved with INTER_AREA and saved as PNG (lossless: the phone decodes the
        very pixels the reference was made of - a JPEG decoder could differ)
  reference.json
        per picture, read back from its PNG: the search (which way up, the five nearest rows with their ids and similarities), rule A2's decision
        with an empty strip, the guards' answer (accept_printing with the picture alone allowed and the index covering the card data) and what
        live_read itself returned (the printing it would add, or null) - the phone's PictureReaderParityTest must give the same
The finish is not compared: with no OCR the server asks the foil network at a fixed place, which the phone does not have (the plan's step 4).
"""
import gzip
import json
import shutil
import sys
from pathlib import Path

import cv2

SERVER = Path.cwd()
ANDROID = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(SERVER / 'src'))

from card_scanner.config import Config  # noqa: E402
from card_scanner.core import image_index, printing_check  # noqa: E402
from card_scanner.core.database import CardDatabase  # noqa: E402
from card_scanner.core.reading.decide import accept_printing, decide_printing  # noqa: E402

OUT = ANDROID / 'app/src/androidTest/assets/picture'
CAPTURES = Path.home() / '.cache/mtg-image-match/truth/rescan_cards'
INDEX = Config.DATA_DIR / 'image_index'

if OUT.exists():
    shutil.rmtree(OUT)
(OUT / 'index').mkdir(parents=True)
(OUT / 'pictures').mkdir()

# --- the index, as a phone downloads it ---
manifest = json.loads((INDEX / image_index.MANIFEST).read_text())
for name in (manifest['hashes'], manifest['ids']):
    shutil.copyfile(INDEX / name, OUT / 'index' / name)
database = CardDatabase()
ids = (INDEX / manifest['ids']).read_text().split()
(OUT / 'index' / 'printings.tsv.gz').write_bytes(image_index.printings_table(ids, database.printing_facts()))
(OUT / 'index' / image_index.MANIFEST).write_text(json.dumps(manifest, indent=1))

# --- the server's reading of each picture, OCR off ---
check = printing_check.PrintingCheck(database, INDEX, Path('/nonexistent/printing_check.jsonl'), 'off', 'off')
cases = []
for path in sorted(CAPTURES.glob('*.jpg')):
    bgr = cv2.imread(str(path))
    half = cv2.resize(bgr, (bgr.shape[1] // 2, bgr.shape[0] // 2), interpolation=cv2.INTER_AREA)
    png = OUT / 'pictures' / (path.stem + '.png')
    cv2.imwrite(str(png), half)
    rgb = cv2.cvtColor(cv2.imread(str(png)), cv2.COLOR_BGR2RGB)          # read back: the reference is of the PNG's own pixels
    search = check._search_now(rgb)
    picture, cards = check._picture_evidence(search)
    decision = decide_printing(picture, {'number': '', 'set': ''})
    card = cards.get(decision['printing_id']) if decision['printing_id'] is not None else None
    facts = {'accept_image_alone': True, 'index_covers': True, 'in_data': bool(card), 'ocr_on': False, 'strip_names': None}
    verdict = accept_printing(decision, facts, {'image_min_similarity': printing_check.IMAGE_MIN_SIMILARITY,
                                                'strong_min_similarity': printing_check.STRONG_MIN_SIMILARITY})
    live = check.live_read(rgb, None, None, None, True)                  # the server's own path, to show that the pieces above are what it does
    added = live['card']['id'] if live else None
    assert added == (decision['printing_id'] if verdict['accepted'] else None), (path.name, added, decision, verdict)
    cases.append({
        'picture': png.name,
        'rotated': bool(search['rotated']),
        'nearest': list(search['nearest']),                              # the nearest row's distance in bits, as given and turned
        'orientation_established': bool(printing_check.orientation_established(search)),
        'candidates': [{'id': c['id'], 'similarity': c['similarity']} for c in picture['candidates']],
        'decision': {'path': decision['path'], 'printing_id': decision['printing_id'], 'similarity': decision['similarity'], 'lead': decision['lead']},
        'accepted': bool(verdict['accepted']), 'refused': verdict['refused'],
        'added': added,
        'added_name': live['card']['name'] if live else None,
    })
reference = {'_note': 'Made by picture_reference.py from the server\'s code - never edit by hand. OCR off: no strip, the picture alone allowed, the index '
                      'covering the card data.', 'generation': manifest['generation'], 'descriptor': manifest['descriptor'],
             'thresholds': {'image_min_similarity': printing_check.IMAGE_MIN_SIMILARITY, 'strong_min_similarity': printing_check.STRONG_MIN_SIMILARITY,
                            'orientation_min_similarity': printing_check.ORIENTATION_MIN_SIMILARITY, 'orientation_min_margin': printing_check.ORIENTATION_MIN_MARGIN},
             'cases': cases}
(OUT / 'reference.json').write_text(json.dumps(reference, indent=1))
added = sum(1 for c in cases if c['added'])
print(f"{len(cases)} pictures: {added} added by the picture alone, {len(cases) - added} not "
      f"({', '.join(sorted({str(c['refused'] or c['decision']['path']) for c in cases if not c['added']}))}); written to {OUT}")
