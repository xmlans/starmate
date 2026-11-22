"""Regenerate the privacy page, images and font subsets used by the Starmate website.

Run from anywhere:  python web/tools/build_assets.py
Needs:              pip install fonttools brotli pillow

What it does
  1. Privacy page: renders PRIVACY.md (repository root) into web/privacy.html using
     web/tools/privacy.template.html, so the policy has a single source.
  2. Screenshots: docs/screenshots/*.png -> web/assets/shots/*.webp, scaled to 540 px wide.
  3. Fonts: subsets the two fonts the app bundles (core/ui/src/main/res/font) down to the
     characters used in web/index.html, web/privacy.html and web/main.js, saved as WOFF2.
     ChillRoundM declares the Reserved Font Names "ChillRoundF" and "ChillRoundM", and a subset
     is a Modified Version under the SIL OFL, so the subset is renamed to "Starmate Round".
  4. Copies both OFL licence texts next to the fonts.

Run it again after changing PRIVACY.md or the page text, otherwise the privacy page is stale
and new characters fall back to a system font.
"""
from pathlib import Path
import html
import re
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
PAGES = ("index.html", "privacy.html", "main.js")


# ---------- privacy page ----------
# PRIVACY.md only uses headings, paragraphs, bullet and numbered lists, tables, **bold**,
# `code` and a "---" line between the Chinese and English halves, so a small converter is enough.

EMAIL = re.compile(r"([A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(?:\.[A-Za-z]{2,})+)")
BULLET = re.compile(r"^[-*] ")
NUMBERED = re.compile(r"^\d+\. ")


def inline(text):
    out = html.escape(text, quote=False)
    out = re.sub(r"\*\*(.+?)\*\*", r"<strong>\1</strong>", out)
    out = re.sub(r"`([^`]+)`", r"<code>\1</code>", out)
    return EMAIL.sub(r'<a href="mailto:\1">\1</a>', out)


def table(rows):
    cells = [[c.strip() for c in row.strip().strip("|").split("|")] for row in rows]
    head, body = cells[0], cells[2:]  # cells[1] is the |---| separator
    parts = ['<div class="table-wrap"><table>', "<thead><tr>"]
    parts += [f"<th>{inline(c)}</th>" for c in head]
    parts.append("</tr></thead><tbody>")
    for row in body:
        parts.append("<tr>" + "".join(f"<td>{inline(c)}</td>" for c in row) + "</tr>")
    parts.append("</tbody></table></div>")
    return "".join(parts)


def blocks(lines):
    out, paragraph, i = [], [], 0

    def flush():
        if paragraph:
            out.append(f"<p>{inline(' '.join(paragraph))}</p>")
            paragraph.clear()

    while i < len(lines):
        line = lines[i].strip()
        heading = re.match(r"^(#{1,3}) (.+)$", line)
        if not line:
            flush()
            i += 1
        elif heading:
            flush()
            tag = {1: "h2", 2: "h3", 3: "h4"}[len(heading.group(1))]
            out.append(f"<{tag}>{inline(heading.group(2))}</{tag}>")
            i += 1
        elif line.startswith("|"):
            flush()
            rows = []
            while i < len(lines) and lines[i].strip().startswith("|"):
                rows.append(lines[i])
                i += 1
            out.append(table(rows))
        elif BULLET.match(line) or NUMBERED.match(line):
            flush()
            marker = BULLET if BULLET.match(line) else NUMBERED
            tag = "ul" if marker is BULLET else "ol"
            items = []
            while i < len(lines) and marker.match(lines[i].strip()):
                items.append(marker.sub("", lines[i].strip()))
                i += 1
            out.append(f"<{tag}>" + "".join(f"<li>{inline(t)}</li>" for t in items) + f"</{tag}>")
        else:
            paragraph.append(line)
            i += 1
    flush()
    return "\n".join(out)


def build_privacy():
    source = (REPO / "PRIVACY.md").read_text(encoding="utf-8")
    halves = re.split(r"^---\s*$", source, flags=re.M)
    if len(halves) != 2:
        raise SystemExit("PRIVACY.md should have exactly one '---' line between Chinese and English")
    articles = []
    for (anchor, lang), text in zip((("zh", "zh-CN"), ("en", "en")), halves):
        articles.append(f'<article class="policy" id="{anchor}" lang="{lang}">\n{blocks(text.splitlines())}\n</article>')
    template = (WEB / "tools" / "privacy.template.html").read_text(encoding="utf-8")
    page = template.replace("<!-- POLICY -->", "\n\n".join(articles))
    (WEB / "privacy.html").write_text(page, encoding="utf-8", newline="\n")
    print(f"  privacy.html: {len(page) / 1024:.0f} KB")


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
    for name in PAGES:
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
    print("privacy page")
    build_privacy()
    print("screenshots")
    build_shots()
    print("fonts")
    build_fonts()
