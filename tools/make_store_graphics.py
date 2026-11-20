#!/usr/bin/env python3
"""Génère l'icône Play 512x512 et l'image de présentation 1024x500 (même motif que l'icône de l'appli)."""
from PIL import Image, ImageDraw, ImageFont
import os
BG, FG, ACC = (11, 61, 92), (255, 255, 255), (79, 195, 247)
out = os.path.join(os.path.dirname(__file__), "..", "store", "graphics")

def bridge(d, cx, cy, span, r, w):
    a, b = (cx - span, cy), (cx + span, cy)
    for p in (a, b):
        d.ellipse([p[0]-r, p[1]-r, p[0]+r, p[1]+r], fill=FG)
    # arche blanche
    d.arc([a[0], cy - span*0.9, b[0], cy + span*0.9], 180, 360, fill=FG, width=w)
    d.line([a[0]+r, cy, b[0]-r, cy], fill=ACC, width=w)

# Icône 512x512 (carré plein : Play applique le masque)
im = Image.new("RGB", (512, 512), BG); d = ImageDraw.Draw(im)
bridge(d, 256, 280, 130, 46, 22)
im.save(os.path.join(out, "icon-512.png"))

# Image de présentation 1024x500
im = Image.new("RGB", (1024, 500), BG); d = ImageDraw.Draw(im)
bridge(d, 270, 280, 150, 48, 22)
def font(sz):
    for f in ("/usr/share/fonts/truetype/dejavu/DejaVuSans-Bold.ttf", "/usr/share/fonts/dejavu/DejaVuSans-Bold.ttf"):
        if os.path.exists(f): return ImageFont.truetype(f, sz)
    return ImageFont.load_default()
d.text((540, 170), "MeshCore", font=font(72), fill=FG)
d.text((540, 255), "Bridge", font=font(72), fill=ACC)
d.text((540, 350), "BLE ↔ TCP pour votre nœud", font=font(28), fill=FG)
im.save(os.path.join(out, "feature-1024x500.png"))
print("ok")
