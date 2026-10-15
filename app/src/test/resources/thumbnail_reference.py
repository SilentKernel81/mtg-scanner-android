# Makes thumbnail_reference.json: the scanner server's comparison of two 32 x 45 card thumbnails
# (station/scanner.py: CardScanner._thumbnail_difference, plain and smooth=True) for the cases in
# ThumbnailParityTest. Run it in the scanner-server checkout with its venv:
#   venv/bin/python ../mtg-scanner-android/app/src/test/resources/thumbnail_reference.py \
#       ../mtg-scanner-android/app/src/test/resources/thumbnail_reference.json
import json, sys
import numpy as np
sys.path.insert(0, 'src')  # the server's code is in src/ of its checkout
from card_scanner.station.scanner import CardScanner

rng = np.random.default_rng(7)
def thumbnail(seed_array):
    t = seed_array.astype(np.float32)
    return ((t - t.mean()) / (t.std() + 1e-6)).astype(np.float32)

def field(scale):
    import cv2
    small = rng.normal(size=(max(2, 45 // scale), max(2, 32 // scale))).astype(np.float32)
    return cv2.resize(small, (32, 45), interpolation=cv2.INTER_CUBIC)

cases = []
for i in range(8):
    a = thumbnail(field(1) * 0.7 + field(4) + field(11))
    if i % 2 == 0:   # the same card a little shifted (an outline's wobble)
        import cv2
        m = np.float32([[1, 0, 0.4 * (i % 3 - 1)], [0, 1, 0.5]])
        b = thumbnail(cv2.warpAffine(a, m, (32, 45), flags=cv2.INTER_LINEAR, borderMode=cv2.BORDER_REPLICATE))
    else:            # another card
        b = thumbnail(field(1) * 0.7 + field(4) + field(11))
    cases.append({'a': a.ravel().tolist(), 'b': b.ravel().tolist(),
                  'plain': CardScanner._thumbnail_difference(a, b),
                  'smooth': CardScanner._thumbnail_difference(a, b, smooth=True)})
json.dump({'width': 32, 'height': 45, 'limit': CardScanner.IMAGE_CHANGE_LIMIT, 'cases': cases}, open(sys.argv[1], 'w'))
