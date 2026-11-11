"""Build macOS icons from the shared logo's existing 1024px iOS rendering.

Requires Pillow. Run generate_app_icons.py first when changing the source SVG.
"""

from pathlib import Path
from PIL import Image, ImageDraw


MOBILE = Path(__file__).resolve().parents[1]
SOURCE = MOBILE / "iosApp/PandaVideo/Assets.xcassets/AppIcon.appiconset/icon-1024.png"
DESTINATION = MOBILE / "desktop/src/main/resources"


def main() -> None:
    # macOS keeps transparent padding around its rounded application icon.
    tile_size = 824
    tile = Image.open(SOURCE).convert("RGBA").resize(
        (tile_size, tile_size), Image.Resampling.LANCZOS
    )
    mask = Image.new("L", tile.size)
    ImageDraw.Draw(mask).rounded_rectangle(
        (0, 0, tile_size - 1, tile_size - 1), radius=180, fill=255
    )
    tile.putalpha(mask)
    icon = Image.new("RGBA", (1024, 1024))
    icon.alpha_composite(tile, (100, 100))
    DESTINATION.mkdir(parents=True, exist_ok=True)
    icon.save(DESTINATION / "pandavideo.png")
    icon.save(DESTINATION / "pandavideo.icns", format="ICNS")


if __name__ == "__main__":
    main()
