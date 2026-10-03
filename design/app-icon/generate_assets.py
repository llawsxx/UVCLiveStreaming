"""Generate Android launcher resources and the editable SVG from shared paths.

Run from any directory with Python and Pillow installed.
"""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

from PIL import Image, ImageDraw


ROOT = Path(__file__).resolve().parents[2]
RES = ROOT / 'app/src/main/res'
ART = Path(__file__).resolve().parent
BACKGROUND = '#101B30'
CYAN = '#35D5EC'
BLUE = '#55A8FF'
WHITE = '#FFFFFF'

# All geometry is shared between SVG, Android vectors and legacy raster icons.
SHAPES = [
    ('M32 39 L63 39 C67 39 70 42 70 46 L70 65 C70 69 67 72 63 72 L32 72 C28 72 25 69 25 65 L25 46 C25 42 28 39 32 39 Z', CYAN, None, 0),
    ('M32 44 L63 44 C64.2 44 65 44.8 65 46 L65 65 C65 66.2 64.2 67 63 67 L32 67 C30.8 67 30 66.2 30 65 L30 46 C30 44.8 30.8 44 32 44 Z', BACKGROUND, None, 0),
    ('M74 49 L81 44.5 C83 43.2 85 44.2 85 46.5 L85 64.5 C85 66.8 83 67.8 81 66.5 L74 62 Z', CYAN, None, 0),
    ('M44 49 C44 48 45 47.5 46 48.2 L56 54.5 C57 55.1 57 55.9 56 56.5 L46 62.8 C45 63.5 44 63 44 62 Z', WHITE, None, 0),
    ('M67 30 C71 30 74 33 74 37', None, BLUE, 4),
    ('M67 22 C75.3 22 82 28.7 82 37', None, BLUE, 4),
]


def svg():
    parts = ['<svg xmlns="http://www.w3.org/2000/svg" width="512" height="512" viewBox="18 18 72 72">',
             f'  <rect x="18" y="18" width="72" height="72" rx="16" fill="{BACKGROUND}"/>',
             '  <g transform="translate(10.8 14.8) scale(0.8)">']
    for path, fill, stroke, width in SHAPES:
        parts.append(f'    <path d="{path}" fill="{fill or "none"}"' +
                     (f' stroke="{stroke}" stroke-width="{width}" stroke-linecap="round"' if stroke else '') + '/>')
    parts.extend(['  </g>', '</svg>'])
    (ART / 'usb-live-studio.svg').write_text('\n'.join(parts) + '\n', encoding='utf-8')


def vector(monochrome=False):
    parts = ['<?xml version="1.0" encoding="utf-8"?>',
             '<vector xmlns:android="http://schemas.android.com/apk/res/android"',
             '    android:width="108dp" android:height="108dp"',
             '    android:viewportWidth="108" android:viewportHeight="108">',
             '    <group android:scaleX="0.8" android:scaleY="0.8" android:translateX="10.8" android:translateY="14.8">']
    for i, (path, fill, stroke, width) in enumerate(SHAPES):
        if i == 1:
            continue
        # An even-odd hole makes the screen transparent in the themed icon too.
        if i == 0:
            path += ' ' + SHAPES[1][0]
        color = WHITE if monochrome and fill else fill
        attrs = [f'android:pathData="{path}"', f'android:fillColor="{color or "#00000000"}"']
        if i == 0:
            attrs.append('android:fillType="evenOdd"')
        if stroke:
            attrs.extend([f'android:strokeColor="{WHITE if monochrome else stroke}"',
                          f'android:strokeWidth="{width}"', 'android:strokeLineCap="round"'])
        parts.append('        <path ' + ' '.join(attrs) + ' />')
    parts.extend(['    </group>', '</vector>'])
    name = 'ic_launcher_monochrome.xml' if monochrome else 'ic_launcher_foreground.xml'
    (RES / 'drawable' / name).write_text('\n'.join(parts) + '\n', encoding='utf-8')


def points(path):
    tokens = re.findall(r'[MLCZ]|-?\d+(?:\.\d+)?', path)
    result = []
    cursor = 0
    while cursor < len(tokens):
        op = tokens[cursor]
        cursor += 1
        if op in ('M', 'L'):
            x, y = map(float, tokens[cursor:cursor + 2])
            cursor += 2
            result.append((x, y))
        elif op == 'C':
            x1, y1, x2, y2, x3, y3 = map(float, tokens[cursor:cursor + 6])
            cursor += 6
            x0, y0 = result[-1]
            for step in range(1, 41):
                t = step / 40
                a = 1 - t
                result.append((a**3*x0 + 3*a*a*t*x1 + 3*a*t*t*x2 + t**3*x3,
                               a**3*y0 + 3*a*a*t*y1 + 3*a*t*t*y2 + t**3*y3))
        elif op == 'Z':
            result.append(result[0])
        else:
            raise ValueError(op)
    return result


def render(size, round_icon=False):
    scale = size * 4 / 72
    image = Image.new('RGBA', (size * 4, size * 4))
    draw = ImageDraw.Draw(image)
    if round_icon:
        draw.ellipse((0, 0, size * 4 - 1, size * 4 - 1), fill=BACKGROUND)
    else:
        draw.rounded_rectangle((0, 0, size * 4 - 1, size * 4 - 1), radius=16*scale, fill=BACKGROUND)
    for path, fill, stroke, width in SHAPES:
        coords = [((x*.8 + 10.8 - 18)*scale, (y*.8 + 14.8 - 18)*scale) for x, y in points(path)]
        if fill:
            draw.polygon(coords, fill=fill)
        if stroke:
            draw.line(coords, fill=stroke, width=round(width*.8*scale), joint='curve')
            radius = width*.8*scale / 2
            for x, y in coords:
                draw.ellipse((x-radius, y-radius, x+radius, y+radius), fill=stroke)
    return image.resize((size, size), Image.Resampling.LANCZOS)


def main():
    svg()
    vector()
    vector(monochrome=True)
    for density, size in [('mdpi', 48), ('hdpi', 72), ('xhdpi', 96), ('xxhdpi', 144), ('xxxhdpi', 192)]:
        directory = RES / f'mipmap-{density}'
        render(size).save(directory / 'ic_launcher.webp', lossless=True)
        render(size, round_icon=True).save(directory / 'ic_launcher_round.webp', lossless=True)
        obsolete = directory / 'ic_launcher_foreground.webp'
        assert obsolete.resolve().is_relative_to(RES.resolve())
        obsolete.unlink(missing_ok=True)
    render(512).save(ART / 'preview.png')
    # Ensure resources and exported SVG are valid XML, and all legacy icons decode.
    ET.parse(ART / 'usb-live-studio.svg')
    for filename in ['ic_launcher_foreground.xml', 'ic_launcher_monochrome.xml']:
        ET.parse(RES / 'drawable' / filename)
    for filename in RES.glob('mipmap-*/ic_launcher*.webp'):
        with Image.open(filename) as image:
            image.verify()
    print('Generated SVG, preview, Android vectors and 10 legacy launcher icons.')


if __name__ == '__main__':
    main()
