"""Draws the launcher icon (a white ball and a gold star on navy) at each density."""
import math
import os
import sys

from PIL import Image, ImageDraw

SIZES = {"mdpi": 48, "hdpi": 72, "xhdpi": 96, "xxhdpi": 144, "xxxhdpi": 192}
res = sys.argv[1]
S = 768  # draw large, then downsample for smooth edges
img = Image.new("RGBA", (S, S), (0, 0, 0, 0))
d = ImageDraw.Draw(img)
d.rounded_rectangle([24, 24, S - 24, S - 24], radius=170, fill=(11, 20, 55, 255))
d.ellipse([130, 170, 470, 510], fill=(255, 255, 255, 255))
d.ellipse([205, 245, 395, 435], outline=(61, 123, 255, 255), width=26)
cx, cy, ro, ri = 500, 500, 165, 68
pts = []
for i in range(10):
    r = ro if i % 2 == 0 else ri
    a = -math.pi / 2 + i * math.pi / 5
    pts.append((cx + r * math.cos(a), cy + r * math.sin(a)))
d.polygon(pts, fill=(245, 197, 24, 255))
for name, px in SIZES.items():
    out = os.path.join(res, "mipmap-" + name)
    os.makedirs(out, exist_ok=True)
    img.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_launcher.png"))
