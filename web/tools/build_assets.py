"""Regenerate the images and font subsets used by the Starmate web page.

Run from anywhere:  python web/tools/build_assets.py
Needs:              pip install fonttools brotli pillow

What it does
  1. Screenshots: docs/screenshots/*.png -> web/assets/shots/*.webp, scaled to 540 px wide.
  2. Fonts: subsets the two fonts the app bundles (core/ui/src/main/res/font) down to the
     characters used in web/index.html and web/main.js, saved as WOFF2.
     ChillRoundM declares the Reserved Font Names "ChillRoundF" and "ChillRoundM", and a subset
     is a Modified Version under the SIL OFL, so the subset is renamed to "Starmate Round".
  3. Copies both OFL licence texts next to the fonts.

Run it again after changing the page text, otherwise new characters fall back to a system font.
"""
from pathlib import Path
import shutil
import string

from fontTools import subset
from PIL import Image

WEB = Path(__file__).resolve().parent.parent
REPO = WEB.parent
FONT_SRC = REPO / "core" / "ui" / "src" / "main" / "res" / "font"
SHOT_SRC = REPO / "docs" / "screenshots"
LICENSE_SRC = REPO / "docs" / "licenses"
FONT_OUT = WEB / "assets" / "fonts"
SHOT_OUT = WEB / "assets" / "shots"

SHOT_WIDTH = 540
EXTRA_CHARS = "“”‘’…·—–、。，！？：；（）《》【】～"


def build_shots():
    SHOT_OUT.mkdir(parents=True, exist_ok=True)
    for png in sorted(SHOT_SRC.glob("*.png")):
        with Image.open(png) as im:
            im = im.convert("RGB")
            height = round(im.height * SHOT_WIDTH / im.width)
            im = im.resize((SHOT_WIDTH, height), Image.LANCZOS)
            out = SHOT_OUT / (png.stem + ".webp")
            im.save(out, "WEBP", quality=82, method=6)
            print(f"  {out.name}: {SHOT_WIDTH}x{height}, {out.stat().st_size / 1024:.0f} KB")


def page_text():
    chars = set(string.printable[:95]) | set(EXTRA_CHARS)
    for name in ("index.html", "main.js"):
        chars |= set((WEB / name).read_text(encoding="utf-8"))
    return "".join(sorted(c for c in chars if c.isprintable() or c == " "))


def rename(font, family, note):
    """Give the font a new family name everywhere it is stored (name table and CFF)."""
    ps_name = family.replace(" ", "") + "-Regular"
    table = font["name"]
    for name_id in (1, 2, 3, 4, 6, 16, 17, 18, 21, 22):
        table.removeNames(nameID=name_id)
    for platform, encoding, language in ((3, 1, 0x409), (1, 0, 0)):
        table.setName(family, 1, platform, encoding, language)
        table.setName("Regular", 2, platform, encoding, language)
        table.setName(ps_name + ";web-subset", 3, platform, encoding, language)
        table.setName(family, 4, platform, encoding, language)
        table.setName(ps_name, 6, platform, encoding, language)
        table.setName(note, 10, platform, encoding, language)
    if "CFF " in font:
        cff = font["CFF "].cff
        cff.fontNames = [ps_name]
        top = cff.topDictIndex[0]
        top.FullName = family
        top.FamilyName = family


def subset_font(source, target, text, new_family=None, note=""):
    options = subset.Options()
    options.flavor = "woff2"
    options.layout_features = ["*"]
    options.name_IDs = ["*"]
    options.name_languages = ["*"]
    options.notdef_outline = True
    options.hinting = False
    options.desubroutinize = True
    font = subset.load_font(str(source), options)
    subsetter = subset.Subsetter(options)
    subsetter.populate(text=text)
    subsetter.subset(font)
    if new_family:
        rename(font, new_family, note)
    subset.save_font(font, str(target), options)
    print(f"  {target.name}: {target.stat().st_size / 1024:.0f} KB")


def build_fonts():
    FONT_OUT.mkdir(parents=True, exist_ok=True)
    text = page_text()
    print(f"  {len(text)} distinct characters on the page")
    subset_font(FONT_SRC / "zcool_kuaile.ttf", FONT_OUT / "kuaile.woff2", text)
    subset_font(
        FONT_SRC / "chill_round_m.otf",
        FONT_OUT / "round.woff2",
        text,
        new_family="Starmate Round",
        note="Subset of ChillRoundM by ChillType for the Starmate website, renamed as the "
             "SIL OFL 1.1 Reserved Font Name clause requires.",
    )
    for name in ("ZCOOLKuaiLe-OFL.txt", "ChillRoundM-OFL.txt"):
        shutil.copy2(LICENSE_SRC / name, FONT_OUT / name)


if __name__ == "__main__":
    print("screenshots")
    build_shots()
    print("fonts")
    build_fonts()
