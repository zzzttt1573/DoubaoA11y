"""
把豆包输入法的图标字体渲染成对照图，并输出字形名称文本。

用法（需要 pip install fonttools pillow）：
    python render_icons.py <豆包APK或ttf文件> <输出目录>

输出：
    icons_<字体名>.png   每个私有区字符一格，格子下方写 Unicode 编码
    icons_<字体名>.txt   每行：编码<TAB>字形名称（字体里的 glyph name，常常带语义）
"""
import io
import sys
import zipfile
from pathlib import Path

from fontTools.ttLib import TTFont
from PIL import Image, ImageDraw, ImageFont

PUA = range(0xE000, 0xF900)


def fonts_from(path: Path):
    if path.suffix.lower() in (".ttf", ".otf"):
        yield path.stem, path.read_bytes()
        return
    with zipfile.ZipFile(path) as z:
        for n in z.namelist():
            if n.lower().endswith((".ttf", ".otf")) and (n.startswith("res/") or "skin" in n):
                yield n.replace("/", "_"), z.read(n)


def render(name: str, data: bytes, out: Path):
    font = TTFont(io.BytesIO(data))
    cmap = font.getBestCmap() or {}
    codes = sorted(c for c in cmap if c in PUA)
    if not codes:
        return
    with open(out / f"icons_{name}.txt", "w", encoding="utf-8") as f:
        for c in codes:
            f.write(f"{c:04x}\t{cmap[c]}\n")
    cell, cols = 96, 12
    rows = (len(codes) + cols - 1) // cols
    img = Image.new("RGB", (cols * cell, rows * cell), "white")
    draw = ImageDraw.Draw(img)
    glyph_font = ImageFont.truetype(io.BytesIO(data), 52)
    label_font = ImageFont.load_default()
    for i, c in enumerate(codes):
        x, y = (i % cols) * cell, (i // cols) * cell
        draw.rectangle([x, y, x + cell - 1, y + cell - 1], outline="#cccccc")
        draw.text((x + cell / 2, y + 40), chr(c), font=glyph_font, fill="black", anchor="mm")
        draw.text((x + 4, y + cell - 14), f"{c:04x}", font=label_font, fill="#c00000")
    img.save(out / f"icons_{name}.png")
    print(f"{name}: {len(codes)} 个私有区字符 -> icons_{name}.png / .txt")


def main():
    src, out = Path(sys.argv[1]), Path(sys.argv[2])
    out.mkdir(parents=True, exist_ok=True)
    for name, data in fonts_from(src):
        try:
            render(name, data, out)
        except Exception as e:  # 某些 ttf 不是图标字体，跳过
            print(f"{name}: 跳过（{e}）")


if __name__ == "__main__":
    main()
