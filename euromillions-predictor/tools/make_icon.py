"""Draws the launcher icon (white ball and gold star on navy) and the notification star at each density."""
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

# Notification icon: white star on transparent, 24dp.
N = 480
star = Image.new("RGBA", (N, N), (0, 0, 0, 0))
ds = ImageDraw.Draw(star)
pts = []
for i in range(10):
    r = 225 if i % 2 == 0 else 95
    a = -math.pi / 2 + i * math.pi / 5
    pts.append((N / 2 + r * math.cos(a), N / 2 + 18 + r * math.sin(a)))
ds.polygon(pts, fill=(255, 255, 255, 255))
for name, scale in {"mdpi": 1, "hdpi": 1.5, "xhdpi": 2, "xxhdpi": 3, "xxxhdpi": 4}.items():
    out = os.path.join(res, "drawable-" + name)
    os.makedirs(out, exist_ok=True)
    px = int(24 * scale)
    star.resize((px, px), Image.LANCZOS).save(os.path.join(out, "ic_notification.png"))
