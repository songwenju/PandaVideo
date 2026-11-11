"""Generate native app, launch and web icons from the shared panda SVG.

Requires Pillow and resvg-py. SVG paths also generate Android vector layers,
so changing the artwork preserves the same face across every platform.
"""

import io
import json
import re
import xml.etree.ElementTree as ET
from pathlib import Path
from PIL import Image
import resvg_py


MOBILE = Path(__file__).resolve().parents[1]
SOURCE = MOBILE.parent / "frontend/static/favicon.svg"
SVG = SOURCE.read_text()


def render(size: int, opaque: bool = False) -> Image.Image:
    artwork = SVG
    if opaque:
        artwork = artwork.replace(
            'x="2" y="2" width="60" height="60" rx="15"',
            'x="0" y="0" width="64" height="64"',
        )
    image = Image.open(io.BytesIO(resvg_py.svg_to_bytes(
        svg_string=artwork, width=size * 4, height=size * 4,
    ))).convert("RGBA")
    return image.resize((size, size), Image.Resampling.LANCZOS)


def vector_paths() -> str:
    paths = []
    panda = ET.fromstring(SVG).find("{http://www.w3.org/2000/svg}g[@id='panda']")
    if panda is None:
        raise ValueError("The shared SVG must contain the panda artwork group")
    transform = re.fullmatch(
        r"translate\(([\d.]+) ([\d.]+)\) scale\(([\d.]+)\)",
        panda.attrib["transform"],
    )
    if transform is None:
        raise ValueError("The panda group must use translate(x y) scale(s)")
    translate_x, translate_y, scale = transform.groups()
    for node in panda.findall("{http://www.w3.org/2000/svg}path"):
        attributes = [f'android:pathData="{node.attrib["d"]}"',
                      f'android:fillColor="{node.get("fill", "#00000000")}"']
        if node.get("stroke"):
            attributes.extend([
                f'android:strokeColor="{node.get("stroke")}"',
                f'android:strokeWidth="{node.get("stroke-width")}"',
                'android:strokeLineCap="round"',
            ])
        paths.append("        <path " + " ".join(attributes) + " />")
    # Preserve the SVG's inset in native vectors as well as raster icons.
    # Reading the source transform prevents future artwork edits from making
    # Android's adaptive and launch icons larger than their iOS/web counterparts.
    return (
        f'    <group android:scaleX="{scale}" android:scaleY="{scale}" '
        f'android:translateX="{translate_x}" android:translateY="{translate_y}">\n'
        + "\n".join(paths) + "\n    </group>"
    )


def main() -> None:
    for density, size in [
        ("mdpi", 48), ("hdpi", 72), ("xhdpi", 96),
        ("xxhdpi", 144), ("xxxhdpi", 192),
    ]:
        directory = MOBILE / f"app/src/main/res/mipmap-{density}"
        directory.mkdir(parents=True, exist_ok=True)
        render(size).save(directory / "ic_launcher.png")

    # iOS applies its own corner mask; the background must fill the opaque square.
    catalog = MOBILE / "iosApp/PandaVideo/Assets.xcassets"
    icons = catalog / "AppIcon.appiconset"
    icons.mkdir(parents=True, exist_ok=True)
    slots = [
        ("iphone", "20x20", "2x"), ("iphone", "20x20", "3x"),
        ("iphone", "29x29", "2x"), ("iphone", "29x29", "3x"),
        ("iphone", "40x40", "2x"), ("iphone", "40x40", "3x"),
        ("iphone", "60x60", "2x"), ("iphone", "60x60", "3x"),
        ("ipad", "20x20", "1x"), ("ipad", "20x20", "2x"),
        ("ipad", "29x29", "1x"), ("ipad", "29x29", "2x"),
        ("ipad", "40x40", "1x"), ("ipad", "40x40", "2x"),
        ("ipad", "76x76", "1x"), ("ipad", "76x76", "2x"),
        ("ipad", "83.5x83.5", "2x"), ("ios-marketing", "1024x1024", "1x"),
    ]
    images = []
    for idiom, size, scale in slots:
        pixels = round(float(size.split("x")[0]) * int(scale[0]))
        filename = f"icon-{pixels}.png"
        image = render(pixels, opaque=True)
        assert image.getchannel("A").getextrema() == (255, 255)
        image.convert("RGB").save(icons / filename)
        images.append(dict(idiom=idiom, size=size, scale=scale, filename=filename))
    info = {"version": 1, "author": "xcode"}
    (catalog / "Contents.json").write_text(json.dumps({"info": info}, indent=2) + "\n")
    (icons / "Contents.json").write_text(
        json.dumps({"images": images, "info": info}, indent=2) + "\n"
    )
    launch = catalog / "LaunchLogo.imageset"
    launch.mkdir(parents=True, exist_ok=True)
    launch_images = []
    for scale in (1, 2, 3):
        filename = f"launch-{scale}x.png"
        render(88 * scale).save(launch / filename)
        launch_images.append(dict(idiom="universal", scale=f"{scale}x", filename=filename))
    (launch / "Contents.json").write_text(json.dumps({"images": launch_images, "info": info}, indent=2) + "\n")
    background = catalog / "LaunchBackground.colorset"
    background.mkdir(parents=True, exist_ok=True)
    (background / "Contents.json").write_text(json.dumps({
        "colors": [
            {"idiom": "universal", "color": {"color-space": "srgb", "components":
                {"red": "1.000", "green": "1.000", "blue": "1.000", "alpha": "1.000"}}},
            {"idiom": "universal", "appearances": [{"appearance": "luminosity", "value": "dark"}],
             "color": {"color-space": "srgb", "components":
                {"red": "0.071", "green": "0.071", "blue": "0.071", "alpha": "1.000"}}},
        ], "info": info,
    }, indent=2) + "\n")
    drawable = MOBILE / "app/src/main/res/drawable"
    (drawable / "ic_launcher_background.xml").write_text("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="64" android:viewportHeight="64">
    <path android:pathData="M0,0H64V64H0Z">
        <aapt:attr xmlns:aapt="http://schemas.android.com/aapt" name="android:fillColor">
            <gradient android:startX="10" android:startY="3" android:endX="54" android:endY="62" android:type="linear">
                <item android:offset="0" android:color="#FF7B3E" />
                <item android:offset="1" android:color="#4ECDC4" />
            </gradient>
        </aapt:attr>
    </path>
</vector>
""")
    paths = vector_paths()
    (drawable / "ic_launcher_foreground.xml").write_text(f"""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="108" android:viewportHeight="108">
    <group android:scaleX="1.1" android:scaleY="1.1" android:translateX="18.8" android:translateY="18.8">
{paths}
    </group>
</vector>
""")
    background_vector = (drawable / "ic_launcher_background.xml").read_text()
    startup_vector = background_vector.replace('108dp', '88dp').replace(
        'M0,0H64V64H0Z',
        'M17,2H47Q62,2 62,17V47Q62,62 47,62H17Q2,62 2,47V17Q2,2 17,2Z',
    ).replace("</vector>", paths + "\n</vector>")
    (drawable / "ic_startup_logo.xml").write_text(startup_vector)
    # Themeable Android icons keep the eye patches as transparent cutouts.
    (drawable / "ic_launcher_monochrome.xml").write_text("""<vector xmlns:android="http://schemas.android.com/apk/res/android"
    android:width="108dp" android:height="108dp"
    android:viewportWidth="64" android:viewportHeight="64">
    <group android:scaleX=".52" android:scaleY=".52" android:translateX="15.36" android:translateY="15.36">
        <path android:fillColor="#FFFFFF" android:pathData="M23,18A8,8 0,1 1,7 18A8,8 0,1 1,23 18ZM57,18A8,8 0,1 1,41 18A8,8 0,1 1,57 18Z" />
        <path android:fillColor="#FFFFFF" android:fillType="evenOdd" android:pathData="M32,15C18,15 10,25 10,37C10,50 20,55 32,55C44,55 54,50 54,37C54,25 46,15 32,15ZM17,29C20,25 25,26 27,30C29,34 26,41 22,42C17,43 13,35 17,29ZM47,29C44,25 39,26 37,30C35,34 38,41 42,42C47,43 51,35 47,29ZM28.5,39.5Q32,38 35.5,39.5Q36.5,41 32,44Q27.5,41 28.5,39.5Z" />
    </group>
</vector>
""")
    web = SOURCE.parent
    for size in (32, 48):
        render(size).save(web / f"favicon-{size}.png")
    render(180, opaque=True).convert("RGB").save(web / "apple-touch-icon.png")
    render(256).save(web / "favicon.ico", sizes=[(16, 16), (32, 32), (48, 48), (256, 256)])


if __name__ == "__main__":
    main()
