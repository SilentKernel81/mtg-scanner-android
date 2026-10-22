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
        and, since 2026-10-21, the finish: whether the server would ask the foil network at the fixed place (orientation established, every tied
        printing prints a marker), the network's probability of the card turned upright, and live_read's own finish
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
from card_scanner.core import foil_marker, image_index, printing_check  # noqa: E402
from card_scanner.core.database import CardDatabase  # noqa: E402
from card_scanner.core.reading.decide import accept_printing, confirm_by_ocr, decide_finish, decide_printing  # noqa: E402
from card_scanner.core import card_ocr  # noqa: E402
sys.path.insert(0, str(SERVER / 'scripts/experiments/ocr_port'))
import ppocr_reference  # noqa: E402

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
# The strip read by the OCR - the phone's TextReader is a port of this reference of light-ocr (its parity test: the same texts), so the phone's strip must be
# THIS strip (since 2026-10-21: the phone reads the strip)
strip_reader = ppocr_reference.Reader()


def with_strip(rgb, search, picture, cards):
    """live_read WITH the strip read by the OCR reference (and the foil network at the OCR's box where it read no symbol): the strip's evidence, what its line
    names (the card data's printing at that set and number - the phone is given it by the test), rule A2, the guards (the picture alone allowed, the index
    covering the card data, OCR ON), and - for a decision by the picture alone (needs_ocr) - the server's full-card confirmation, as Identification._ocr_names
    does it: the card read whole as given (card_ocr.parse_magic of the OCR reference's lines), printing_check.ocr_facts (the lookup through
    printing_named_by_line) and confirm_by_ocr with the decision's lead; the finish"""
    upright = cv2.rotate(rgb, cv2.ROTATE_180) if search['rotated'] else rgb
    strip = printing_check.read_strip_evidence(strip_reader, upright, net)
    decision = decide_printing(picture, {'number': strip['number'], 'set': strip['set']})
    card = cards.get(decision['printing_id']) if decision['printing_id'] is not None else None
    named = check._strip_printing(strip)
    line = None if named is printing_check.NO_LINE else 'lookup_failed' if named is printing_check.LOOKUP_FAILED else (named['id'] if named else '')
    alone = decision['path'] == 'image'
    facts = {'accept_image_alone': True, 'index_covers': True, 'in_data': bool(card), 'ocr_on': True,
             'strip_names': check._strip_names(search['index'], strip, decision['printing_id']) if alone and card else None}
    verdict = accept_printing(decision, facts, {'image_min_similarity': printing_check.IMAGE_MIN_SIMILARITY,
                                                'strong_min_similarity': printing_check.STRONG_MIN_SIMILARITY})
    full = None
    if verdict['needs_ocr']:
        # Identification._ocr_names: the card as given, read whole; its facts; the decision
        lines = strip_reader.read_lines(rgb)
        read = card_ocr.parse_magic(lines, rgb.shape[1], rgb.shape[0])
        printing_at = lambda set_code, number: printing_check.printing_named_by_line(database.get_card_by_set_number, set_code, number)
        ocr = printing_check.ocr_facts(read, card, printing_at, {'number': strip['number'], 'set': strip['set']})
        # the printings the facts were looked up as (the phone is given the card data's answers by the test, as for the strip)
        lookups = {}
        for s, n in ((read or {}).get('set_code'), (read or {}).get('collector_number')), (strip['set'], strip['number']):
            if s and n:
                found = printing_at(s, n)
                lookups[f"{s.lower()} {n}"] = {'id': found['id'], 'name': found['name'], 'flavor_name': found.get('flavor_name'),
                                                'oracle_id': found.get('oracle_id')} if found else None
        confirmed = confirm_by_ocr(ocr, decision['lead'])
        full = {'read': read, 'facts': ocr, 'lookups': lookups, 'card': {'name': card['name'], 'flavor_name': card.get('flavor_name')},
                'accepted': confirmed['accepted'], 'refused': confirmed['refused']}
        verdict = {'accepted': confirmed['accepted'], 'refused': confirmed['refused'], 'needs_ocr': False}
    return {'strip': strip, 'strip_printing': line,              # None: no whole line; '': the card data has none; an id; 'lookup_failed'
            'decision': {'path': decision['path'], 'printing_id': decision['printing_id']},
            'strip_names': facts['strip_names'], 'accepted': bool(verdict['accepted']), 'refused': verdict['refused'], 'full_card': full,
            'finish': decide_finish(card.get('finishes'), strip['symbol'], strip['net']) if verdict['accepted'] and card else None}
net = foil_marker.load()
assert net is not None, 'the foil network (core/foil_net.onnx) did not load'
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
    live = check.live_read(rgb, None, None, net, True)                   # the server's own path (OCR off, the foil network given), to show that the pieces above are what it does
    # the finish: the network asked at the fixed place of the card turned upright, when the server would ask it (live_read's net_at_a_fixed_place)
    asks = bool(printing_check.orientation_established(search) and check.marker_expected(search))
    upright = cv2.rotate(rgb, cv2.ROTATE_180) if search['rotated'] else rgb
    crop = foil_marker.fixed_crop(upright)
    probability = float(net.probability(crop)) if crop is not None else None
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
        'marker_expected': asks,
        'net': probability,                                              # computed for every picture; the server ASKS it only where marker_expected
        'finishes': list((card or {}).get('finishes') or []),
        'finish': live['finish'] if live else None,                      # decide_finish's answer: {'finish', 'source'}
        'with_strip': with_strip(rgb, search, picture, cards),
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
