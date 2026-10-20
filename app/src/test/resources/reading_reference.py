"""
Makes the reference files of the reading port (com.cardscanner.reading: Decide.kt, Fingerprint.kt, FoilNet.kt) from the scanner server's own code. Run it from the server's
checkout with its venv, as the other *_reference.py scripts here:

    cd ../scanner-server && venv/bin/python ../mtg-scanner-android/app/src/test/resources/reading_reference.py

What it writes (never edit these by hand - run this again):
  app/src/test/resources/reading_decision_cases.json, reading_regression_cases.json, reading_finish_cases.json
        COPIES of the server's tests/data files: the same cases its Python tests pass (decide_printing, decide_finish)
  app/src/test/resources/foil_math_reference.json
        the foil network's arithmetic (JVM tests): a crop as the network's input, the softmax of its outputs
  app/src/test/resources/fingerprint_reference.json
        the fingerprint's arithmetic (JVM tests): the percentile stretch of a plane, the bits of a 16 x 16 block of DCT frequencies, similarity of a Hamming distance
  app/src/androidTest/assets/parity/fp_*.png, fingerprint_hashes.json
        pictures (PNG: lossless, no decoder differences) and the server's fingerprint of each, read back from the PNG
  app/src/androidTest/assets/parity/foil_reference.json
        crops of set lines and the foil network's probability of each, and the SHA-256 of the network file
  app/src/main/assets/foil_net.onnx
        a COPY of the server's core/foil_net.onnx
"""
import base64
import hashlib
import json
import shutil
import sys
from pathlib import Path

import cv2
import numpy as np

SERVER = Path.cwd()
ANDROID = Path(__file__).resolve().parents[4]
sys.path.insert(0, str(SERVER / 'src'))
sys.path.insert(1, str(SERVER / 'tests'))

from card_scanner.core import foil_marker, image_index  # noqa: E402
from test_printing_check import PINNED, synthetic_card  # noqa: E402

RESOURCES = ANDROID / 'app/src/test/resources'
PARITY = ANDROID / 'app/src/androidTest/assets/parity'

# ---- the shared case files: copies
for name in ('reading_decision_cases.json', 'reading_regression_cases.json', 'reading_finish_cases.json'):
    shutil.copyfile(SERVER / 'tests/data' / name, RESOURCES / name)

# ---- the fingerprint's arithmetic
rng = np.random.default_rng(2026)
stretch = []
for seed, (h, w), kind in ((1, (20, 15), 'noise'), (2, (30, 40), 'noise'), (3, (10, 10), 'constant'), (4, (12, 12), 'two values'), (5, (50, 50), 'outliers'), (6, (16, 16), 'narrow')):
    if kind == 'noise':
        plane = np.random.default_rng(seed).integers(0, 256, (h, w), dtype=np.uint8)
    elif kind == 'constant':
        plane = np.full((h, w), 77, np.uint8)
    elif kind == 'two values':
        plane = np.where(np.arange(h * w).reshape(h, w) % 3 == 0, 200, 40).astype(np.uint8)
    elif kind == 'outliers':
        plane = np.random.default_rng(seed).integers(100, 140, (h, w), dtype=np.uint8)
        plane[0, :5] = 0
        plane[-1, -5:] = 255
    else:
        plane = np.random.default_rng(seed).integers(120, 122, (h, w), dtype=np.uint8)       # high - low < 1: the divisor is 1
    stretch.append({'kind': kind, 'width': w, 'height': h, 'plane': plane.flatten().tolist(), 'expected': image_index.stretch_plane(plane).flatten().tolist()})

bits = []
for seed in range(1, 7):
    small = np.random.default_rng(seed).integers(0, 256, (64, 64), dtype=np.uint8)
    low = cv2.dct(small.astype(np.float32))[:16, :16]
    bits.append({'low': [float(x) for x in low.flatten()], 'expected_hex': image_index.channel_bits(low).tobytes().hex()})
flat = np.full((16, 16), 3.5, np.float32)                                                    # every value equals the median: nothing is above it
bits.append({'low': [float(x) for x in flat.flatten()], 'expected_hex': image_index.channel_bits(flat).tobytes().hex()})
ties = np.arange(256, dtype=np.float32).reshape(16, 16) // 4                                  # runs of equal values around the median
bits.append({'low': [float(x) for x in ties.flatten()], 'expected_hex': image_index.channel_bits(ties).tobytes().hex()})

distances = [0, 1, 96, 383, 384, 385, 768]
reference = {'stretch': stretch, 'bits': bits, 'distances': distances, 'similarities': [float(image_index.similarity(d)) for d in distances],
             'edge': list(image_index.EDGE), 'card_size': list(image_index.CARD_SIZE), 'hash_bytes': image_index.HASH_BYTES, 'descriptor': image_index.DESCRIPTOR}
(RESOURCES / 'fingerprint_reference.json').write_text(json.dumps(reference))

# ---- the foil network's own arithmetic (JVM tests): a crop as the network's input, and the softmax of its two outputs
fixture_crops = np.load(SERVER / 'tests/data/foil_marker_crops.npz')['crops']
normalised = []
for i in (0, 5, 11):
    crop = fixture_crops[i]
    x = crop.astype(np.float32) / 255.0                                  # exactly what FoilMarker.probability does
    x = (x - x.mean()) / (x.std(ddof=1) + 1e-3)
    normalised.append({'pixels_b64': base64.b64encode(crop.tobytes()).decode(), 'expected': [float(v) for v in x.flatten()]})
softmax = []
for logits in ([0.3, -0.2], [5.0, -5.0], [-3.0, 4.0], [0.0, 0.0], [100.0, 99.0], [-100.0, -99.0]):
    l = np.array(logits, np.float32)
    e = np.exp(l - l.max())
    softmax.append({'logits': logits, 'expected': float(e[1] / e.sum())})
(RESOURCES / 'foil_math_reference.json').write_text(json.dumps({'normalised': normalised, 'softmax': softmax}))

# ---- whole pictures, as PNG, and the server's fingerprint of each as read back
PARITY.mkdir(parents=True, exist_ok=True)
pictures = {}
for seed in (1234, 99):
    pictures[f'fp_synthetic_{seed}.png'] = synthetic_card(seed)             # RGB
capture = sorted((Path.home() / '.cache/mtg-image-match/truth/rescan_cards').glob('phone-0240eb84_1037_*.jpg'))[0]
real = cv2.cvtColor(cv2.imread(str(capture)), cv2.COLOR_BGR2RGB)
scale = 504 / real.shape[0]
pictures['fp_capture.png'] = cv2.resize(real, None, fx=scale, fy=scale, interpolation=cv2.INTER_AREA)
hashes = {}
for name, rgb in pictures.items():
    cv2.imwrite(str(PARITY / name), cv2.cvtColor(rgb, cv2.COLOR_RGB2BGR))
    back = cv2.cvtColor(cv2.imread(str(PARITY / name)), cv2.COLOR_BGR2RGB)
    assert np.array_equal(back, rgb), f'{name}: a PNG must read back exactly'
    hashes[name] = image_index.hash_card(back).hex()
    hashes[name] = {'width': back.shape[1], 'height': back.shape[0], 'hash_hex': hashes[name]}
for seed in (1234, 99):                                                     # tied to the server's own pin: the asset is the picture that pin is of
    assert hashes[f'fp_synthetic_{seed}.png']['hash_hex'] == PINNED[seed], f'synthetic {seed} no longer gives the server\'s pinned fingerprint'
(PARITY / 'fingerprint_hashes.json').write_text(json.dumps(hashes, indent=1))

# ---- the foil network
network = SERVER / 'src/card_scanner/core/foil_net.onnx'
shutil.copyfile(network, ANDROID / 'app/src/main/assets/foil_net.onnx')
marker = foil_marker.FoilMarker()
fixture = np.load(SERVER / 'tests/data/foil_marker_crops.npz')
chosen = list(range(0, 16))
crops = []
for i in chosen:
    crop = fixture['crops'][i]
    probability = marker.probability(crop)
    assert abs(probability - float(fixture['probability'][i])) < 1e-4, 'the fixture and the network disagree: re-make the fixture'
    crops.append({'label': int(fixture['foil'][i]), 'pixels_b64': base64.b64encode(crop.tobytes()).decode(), 'probability': probability})
(PARITY / 'foil_reference.json').write_text(json.dumps({'onnx_sha256': hashlib.sha256(network.read_bytes()).hexdigest(), 'width': 128, 'height': 32, 'crops': crops}))

print('written:', *(str(p.relative_to(ANDROID)) for p in sorted([*RESOURCES.glob('reading_*.json'), RESOURCES / 'fingerprint_reference.json',
                                                                 *PARITY.glob('fp_*'), PARITY / 'fingerprint_hashes.json', PARITY / 'foil_reference.json',
                                                                 ANDROID / 'app/src/main/assets/foil_net.onnx'])), sep='\n  ')
