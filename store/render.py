# /// script
# dependencies = ["cairosvg==2.8.2", "pillow==11.3.0"]
# ///
"""store/ 의 SVG 원본으로 Play 스토어 그림을 만든다.

부르는 법(topics/app/ 에서): uv run store/render.py
- icon-512.png: 512×512, RGBA(32-bit) PNG. 모서리는 Play 가 깎는다.
- feature-1024x500.png: 1024×500, RGB PNG(알파 없음).
글자는 Noto Sans CJK KR 글꼴(fontconfig 가 찾는 시스템 글꼴)로 그린다.
"""

import io
from pathlib import Path

import cairosvg
from PIL import Image

STORE = Path(__file__).resolve().parent


def render(svg: str, out: str, size: tuple[int, int], mode: str) -> None:
    png = cairosvg.svg2png(url=str(STORE / svg), output_width=size[0], output_height=size[1])
    image = Image.open(io.BytesIO(png)).convert(mode)
    assert image.size == size, image.size
    image.save(STORE / out, optimize=True)
    print(f"{out}: {image.size[0]}x{image.size[1]} {image.mode} {(STORE / out).stat().st_size} bytes")


render("icon.svg", "icon-512.png", (512, 512), "RGBA")
render("feature.svg", "feature-1024x500.png", (1024, 500), "RGB")
