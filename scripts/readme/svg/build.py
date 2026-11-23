"""Builds the README's SVG images into docs/readme/: banner, how-it-works (needs compose.mjs's parts) and
architecture. Run with a Python that has fontTools (see ../README.md)."""
import json, os, sys
sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import architecture, banner, howitworks
from kit import CACHE, TOK

geom_file = os.path.join(CACHE, 'parts', 'hiw-geom.json')
if not os.path.exists(geom_file):
    raise SystemExit('missing .cache/parts/hiw-geom.json: run node compose.mjs first')
with open(geom_file) as fh:
    geom = json.load(fh)
for theme in TOK:
    banner.build(theme)
    howitworks.build(theme, geom)
    architecture.build(theme)
