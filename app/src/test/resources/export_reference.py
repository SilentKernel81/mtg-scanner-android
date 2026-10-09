# Makes export_reference.json: the scanner server's collection CSV (games/base.py:
# write_collection_csv) for the rows in ExportParityTest. Run it in the scanner-server checkout
# with its venv:
#   venv/bin/python ../mtg-scanner-android/app/src/test/resources/export_reference.py \
#       ../mtg-scanner-android/app/src/test/resources/export_reference.json
import io, json, sys
sys.path.insert(0, '.')
from games.base import write_collection_csv
rows = [
    dict(card_id='0a7b2e5c-1111-4222-8333-444455556666', set_code='hoc',
         name='Thorin, King of Durin\'s Folk', set_name='The Hobbit Eternal', number='3', rarity='rare',
         type_line='Legendary Creature — Dwarf Noble', mana_cost='{3}{R}{W}', colors='R, W', color_identity='Multicolor',
         price=6.07, quantity=1, condition='Near Mint', finish='regular', timestamp='2026-09-25 21:24:47'),
    dict(card_id=None, set_code='tst', name='The "Quoted" Card', set_name='Test', number='12a', rarity='common', type_line='Instant',
         mana_cost='{U}', colors='U', color_identity='Blue', price=0.5, quantity=3, condition='Lightly Played',
         finish='foil', timestamp='2026-09-26 10:00:00'),
    dict(card_id='9f8e7d6c-aaaa-4bbb-8ccc-ddddeeeeffff', set_code='hob',
         name='Smaug', set_name='The Hobbit', number='109', rarity='common', type_line='Legendary Creature — Dragon',
         mana_cost='{5}{R}{R}', colors='R', color_identity='Red', price=0.0, quantity=2, condition='Near Mint',
         finish='surge', timestamp='2026-09-26 11:00:00'),
]
out = io.StringIO(); write_collection_csv(rows, out)
json.dump({'rows': rows, 'csv': out.getvalue()}, open(sys.argv[1], 'w'), ensure_ascii=False, indent=1)
