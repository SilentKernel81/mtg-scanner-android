"""
Makes the reference files of the OCR port (com.cardscanner.reading: TextMath.kt, CardText.kt; the server's docs/plans/ANDROID_OCR_PLAN.md, step 2) from the
scanner server's own code: its Python reference of light-ocr's reading (scripts/experiments/ocr_port/ppocr_reference.py) and its parsers
(core/card_ocr.py, core/foil_marker.py). Run it from the server's checkout with its venv, as reading_reference.py:

    cd ../scanner-server && venv/bin/python ../mtg-scanner-android/app/src/test/resources/ocr_reference.py

What it writes (never edit these by hand - run this again):
  app/src/main/assets/ocr/det.onnx, rec.onnx, dictionary.json, the licences   COPIES of the server's light-ocr bundle, checked against its SHA256SUMS
  app/src/test/resources/ocr_math_reference.json
        the pure arithmetic of the pipeline, cases computed by the reference's own functions: Clipper's round offset of a quad (the grown box: the points
        and their min-area rectangle's corners), the clockwise order, the reading order, the recognizer's tensor width, the area / perimeter / distance
        of a box, and CTC decoding of small probability tables (a short dictionary of its own)
  app/src/test/resources/ocr_parser_reference.json
        LINES light-ocr really read on 30 pictures (the card and its strip: texts and boxes) and hand-written ones (a token, 123/281, look-alike digits, a
        name with stray mana symbols, a star, a dot, Unicode spaces) with what the server's parsers make of them: parse_magic, parse_strip, set_line's
        index, symbol_read - the Kotlin parsers must give exactly these
"""
import hashlib
import json
import random
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

SERVER = Path.cwd()
ANDROID = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(SERVER / 'src'))
sys.path.insert(0, str(SERVER / 'scripts/experiments/ocr_port'))

from card_scanner.core import card_ocr, foil_marker  # noqa: E402
import ppocr_reference as pr  # noqa: E402

RESOURCES = ANDROID / 'app/src/test/resources'
ASSETS = ANDROID / 'app/src/main/assets/ocr'
BUNDLE = pr.BUNDLE

# --- the model files: copies, checked ---
sums = dict(reversed(line.split()) for line in (BUNDLE / 'SHA256SUMS').read_text().splitlines() if line.strip())
ASSETS.mkdir(parents=True, exist_ok=True)
for source, target in (('det/inference.onnx', 'det.onnx'), ('rec/inference.onnx', 'rec.onnx'), ('rec/dictionary.json', 'dictionary.json')):
    data = (BUNDLE / source).read_bytes()
    assert hashlib.sha256(data).hexdigest() == sums[source], source
    (ASSETS / target).write_bytes(data)
for source, target in (('LICENSES/MODEL-NOTICE.md', 'MODEL-NOTICE.md'), ('LICENSES/PaddleOCR-Apache-2.0.txt', 'LICENSE-PaddleOCR.txt'),
                       ('../LICENSE', 'LICENSE-light-ocr.txt'), ('../NOTICE', 'NOTICE.txt')):
    shutil.copyfile(BUNDLE / source, ASSETS / target)

# --- the arithmetic ---
reader = pr.Reader()
rng = random.Random(11)
quads = []
for _ in range(40):          # text-line-shaped quads: long, thin, a little tilted; some tiny, some upright, some at whole numbers
    cx, cy, w, h, angle = rng.uniform(20, 900), rng.uniform(20, 1300), rng.choice([rng.uniform(3, 30), rng.uniform(30, 700)]), rng.uniform(3, 60), rng.uniform(-8, 8)
    quads.append(cv2.boxPoints(((cx, cy), (w, h), angle)).astype(np.float32))
quads.append(np.array([[10, 10], [110, 10], [110, 40], [10, 40]], np.float32))            # an axis-aligned box at whole numbers
quads.append(np.array([[0, 0], [5, 0], [5, 3], [0, 3]], np.float32))                      # tiny, at the picture's corner
grow_cases = []
for box in quads:
    area = abs(cv2.contourArea(box))
    perimeter = cv2.arcLength(box.reshape(-1, 1, 2), True)
    distance = area * reader.unclip_ratio / perimeter
    points = pr.clipper_offset_round(box.tolist(), distance)
    rect = cv2.boxPoints(cv2.minAreaRect(np.array(points, np.float32)))
    grow_cases.append({'box': box.tolist(), 'area': area, 'perimeter': perimeter, 'distance': distance, 'points': points,
                       'mini_box': pr.mini_box(np.array(points, np.float32).reshape(-1, 1, 2))[0].tolist(), 'rect_corners_sorted': sorted(rect.tolist())})
clockwise_cases = []
for _ in range(20):
    pts = np.array([[rng.uniform(0, 500), rng.uniform(0, 500)] for _ in range(4)], np.float32)
    clockwise_cases.append({'points': pts.tolist(), 'ordered': pr.order_clockwise(pts).tolist()})
clockwise_cases.append({'points': [[5, 5], [5, 1], [1, 1], [1, 5]], 'ordered': pr.order_clockwise(np.array([[5, 5], [5, 1], [1, 1], [1, 5]], np.float32)).tolist()})
order_cases = []
for lines in ([[[100, 50], [0, 52], [200, 49]]], [[[0, 0], [50, 15], [10, 5], [70, 100], [20, 95]]], [[[30, 10], [10, 19], [50, 28], [0, 37]]]):
    corners = [np.array([[x, y], [x + 10, y], [x + 10, y + 5], [x, y + 5]], np.float32) for x, y in lines[0]]
    ordered = pr.reading_order(list(corners), reader.row_band)
    order_cases.append({'top_lefts': lines[0], 'band': reader.row_band, 'order': [lines[0].index([int(q[0][0]), int(q[0][1])]) for q in ordered]})
width_cases = [{'height': h, 'width': w, 'tensor_and_content': list(reader.tensor_width(h, w))}
               for h, w in ((48, 320), (48, 100), (32, 400), (30, 20), (25, 4000), (31, 207), (45, 301), (40, 1), (17, 113), (48, 321))]
small_dictionary = ['a', 'b', 'c', ' ', '★']
ctc_cases = []
for steps, picks in (([1, 1, 0, 1, 2, 2], None), ([0, 0, 0], None), ([3, 4, 4, 0, 4, 5], None), ([1, 0, 1, 1, 2], None)):
    probs = np.full((len(steps), len(small_dictionary) + 1), 0.01, np.float32)
    for t, k in enumerate(steps):
        probs[t, k] = 0.9 - 0.05 * t
    text, confidence, indices = pr.ctc_decode(probs, small_dictionary)
    ctc_cases.append({'probabilities': probs.tolist(), 'text': text, 'confidence': confidence, 'indices': indices})
(RESOURCES / 'ocr_math_reference.json').write_text(json.dumps({
    '_note': 'Made by ocr_reference.py from the server\'s scripts/experiments/ocr_port/ppocr_reference.py - never edit by hand.',
    'unclip_ratio': reader.unclip_ratio, 'grow': grow_cases, 'clockwise': clockwise_cases, 'reading_order': order_cases,
    'tensor_width': width_cases, 'ctc': {'dictionary': small_dictionary, 'cases': ctc_cases}}, indent=1, ensure_ascii=False))

# --- the parsers: light-ocr's real lines, and hand-written ones ---
from compare_with_light_ocr import LightOcr  # noqa: E402
T = Path.home() / '.cache/mtg-image-match/truth'
pictures = sorted((T / 'rescan_cards').glob('*.jpg'))[:15] + sorted((T / 'captures').glob('*.jpg'))[:15]
light = LightOcr()
parser_cases = []


def parsed(name, lines, width, height):
    """What the server's parsers make of `lines` (the card's: parse_magic; the strip's: parse_strip, set_line, symbol_read)"""
    line = foil_marker.set_line(lines)
    return {'name': name, 'width': width, 'height': height, 'lines': lines,
            'parse_magic': card_ocr.parse_magic(lines, width, height),
            'parse_strip': list(card_ocr.parse_strip(lines)),
            'set_line_index': lines.index(line) if line else None,
            'symbol_read': foil_marker.symbol_read(line) if line else None}


for path in pictures:
    rgb = cv2.cvtColor(cv2.imread(str(path)), cv2.COLOR_BGR2RGB)
    strip = foil_marker.strip_of(rgb)
    parser_cases.append(parsed(path.name, light.read_lines(rgb), rgb.shape[1], rgb.shape[0]))
    parser_cases.append(parsed(path.name + ' (strip)', light.read_lines(strip), strip.shape[1], strip.shape[0]))
light.close()


def line(text, x, y, w=200, h=20):
    return {'text': text, 'confidence': 0.9, 'box': [[x, y], [x + w, y], [x + w, y + h], [x, y + h]]}


W, H = 1000, 1400
hand = [
    ('a token', [line('Soldier', 60, 80), line('T 0001', 30, 1300), line('FRA • EN JOHN AVON', 30, 1330)]),
    ('123/281 of an older card', [line('Lightning Bolt', 60, 80), line('123/281 C', 30, 1300), line('M10 • EN CHRISTOPHER MOELLER', 30, 1330)]),
    ('look-alike digits', [line('Gandalf', 60, 80), line('U O172', 30, 1300), line('HOB★EN', 30, 1330)]),
    ('stray mana symbols after the name', [line('Healer **', 60, 80), line('C 0042', 30, 1300), line('LTR·EN', 30, 1330)]),
    ('a dot', [line('Forest', 60, 80), line('L 0278', 30, 1300), line('DMU • EN', 30, 1330)]),
    ('a star with spaces', [line('Sol Ring', 60, 80), line('M 0005', 30, 1300), line('SLD * EN', 30, 1330)]),
    ('no marker read', [line('Elven Passage', 60, 80), line('C 0181', 30, 1300), line('HOBEN', 30, 1330)]),
    ('a Unicode space', [line('Arwen Undomiel', 60, 80), line('R 0012', 30, 1300), line('LTR · EN', 30, 1330)]),
    ('no title', [line('C 0181', 30, 1300)]),
    ('a number with a letter', [line('Gollum', 60, 80), line('R 0245p', 30, 1300), line('LTR★EN', 30, 1330)]),
    ('the set line before the number', [line('Frodo', 60, 80), line('LTR·EN', 30, 1290), line('U 0451', 30, 1330)]),
]
for name, lines in hand:
    parser_cases.append(parsed(name, lines, W, H))
(RESOURCES / 'ocr_parser_reference.json').write_text(json.dumps({
    '_note': 'Made by ocr_reference.py: light-ocr\'s real lines of 30 pictures (the card and its strip) and hand-written ones, with the answers of the '
             'server\'s card_ocr.parse_magic / parse_strip and foil_marker.set_line / symbol_read - never edit by hand.',
    'cases': parser_cases}, indent=1, ensure_ascii=False))
print(f"{len(grow_cases)} grow, {len(clockwise_cases)} clockwise, {len(order_cases)} reading-order, {len(width_cases)} width, {len(ctc_cases)} CTC cases; "
      f"{len(parser_cases)} parser cases")
