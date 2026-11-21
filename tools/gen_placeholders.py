#!/usr/bin/env python3
"""Draws the placeholder textures and synthesises the placeholder sounds of the Intercom pack.

usage: gen_placeholders.py [--no-sounds]

Everything this writes is meant to be replaced by real art/audio; the file names, sizes and nine-slice settings
are listed in the README ("Replacing textures and sounds"). Re-running overwrites the files. The sounds need ffmpeg
on the PATH (WAV is synthesised here, ffmpeg encodes OGG Vorbis).
"""
import math
import os
import struct
import subprocess
import sys
import tempfile
import wave

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
from pnglib import write_png  # noqa: E402

ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), "..", "src", "main", "resources")
PACK = os.path.join(ROOT, "pack", "assets", "intercom")
OPTIONAL = os.path.join(ROOT, "pack-optional", "global-buttons", "assets", "minecraft", "textures", "gui", "sprites", "widget")

# These three numbers are also in dialog/DialogSkin.java and font/ui.json - change them together.
BUTTON_W, BUTTON_H = 108, 18          # a 110 x 20 dialog button minus its 1 px frame
# panel: 4 px above the hint line + 9 line + 4 + 10 gap + 48 text box (dialog.input-height) = 75
PANEL_W, PANEL_H = 240, 75


class Canvas:
    def __init__(self, w, h, fill=(0, 0, 0, 0)):
        self.w, self.h = w, h
        self.px = [[fill] * w for _ in range(h)]

    def rect(self, x0, y0, x1, y1, c):
        for y in range(max(0, y0), min(self.h, y1)):
            for x in range(max(0, x0), min(self.w, x1)):
                self.px[y][x] = c

    def frame(self, x0, y0, x1, y1, c):
        self.rect(x0, y0, x1, y0 + 1, c)
        self.rect(x0, y1 - 1, x1, y1, c)
        self.rect(x0, y0, x0 + 1, y1, c)
        self.rect(x1 - 1, y0, x1, y1, c)

    def save(self, path):
        os.makedirs(os.path.dirname(path), exist_ok=True)
        write_png(path, self.w, self.h, self.px)


def plate(w, h, face, edge, shine):
    """A flat plate: 1 px edge, a lighter line under the top edge."""
    c = Canvas(w, h, face)
    c.frame(0, 0, w, h, edge)
    c.rect(1, 1, w - 1, 2, shine)
    return c


def textures():
    font = os.path.join(PACK, "textures", "font")
    # banner background: stretched over the whole banner by the text shader, so only flat fills/gradients make sense
    Canvas(8, 8, (0, 0, 0, 255)).save(os.path.join(font, "banner_bg.png"))

    plate(BUTTON_W, BUTTON_H, (24, 58, 44, 255), (84, 176, 120, 255), (44, 92, 68, 255)).save(os.path.join(font, "button_send.png"))
    plate(BUTTON_W, BUTTON_H, (40, 42, 48, 255), (112, 118, 130, 255), (60, 63, 72, 255)).save(os.path.join(font, "button_cancel.png"))

    # dialog panel: dark plate, amber strip on top
    p = Canvas(PANEL_W, PANEL_H, (14, 16, 20, 236))
    p.frame(0, 0, PANEL_W, PANEL_H, (70, 78, 92, 255))
    p.rect(1, 1, PANEL_W - 1, 3, (232, 160, 40, 255))
    p.save(os.path.join(font, "dialog_panel.png"))

    # optional global button skin (vanilla sprite size 200 x 20, nine-slice border 3)
    plate(200, 20, (30, 32, 38, 255), (92, 98, 112, 255), (48, 51, 60, 255)).save(os.path.join(OPTIONAL, "button.png"))
    plate(200, 20, (38, 41, 48, 255), (232, 160, 40, 255), (64, 62, 54, 255)).save(os.path.join(OPTIONAL, "button_highlighted.png"))
    plate(200, 20, (22, 23, 26, 255), (50, 52, 58, 255), (26, 27, 31, 255)).save(os.path.join(OPTIONAL, "button_disabled.png"))
    for name in ("button", "button_highlighted", "button_disabled"):
        with open(os.path.join(OPTIONAL, name + ".png.mcmeta"), "w") as f:
            f.write('{\n    "gui": {\n        "scaling": {\n            "type": "nine_slice",\n            "width": 200,\n'
                    '            "height": 20,\n            "border": 3\n        }\n    }\n}\n')


RATE = 44100


def bell(freq, seconds, volume=0.5):
    """A struck-bell tone: the fundamental plus two quieter partials, fast attack, exponential decay."""
    out = []
    for i in range(int(RATE * seconds)):
        t = i / RATE
        env = min(1.0, t / 0.004) * math.exp(-t * 5.5)
        s = math.sin(2 * math.pi * freq * t) + 0.35 * math.sin(2 * math.pi * freq * 2.0 * t) + 0.12 * math.sin(2 * math.pi * freq * 3.01 * t)
        out.append(volume * env * s / 1.47)
    return out


def mix(parts, total_seconds):
    """parts: (start_seconds, samples)"""
    buf = [0.0] * int(RATE * total_seconds)
    for start, samples in parts:
        o = int(start * RATE)
        for i, s in enumerate(samples):
            if o + i < len(buf):
                buf[o + i] += s
    fade = int(RATE * 0.02)
    for i in range(fade):
        buf[-1 - i] *= i / fade
    return buf


def corrupt(samples):
    """The chime as a damaged machine would play it: sample-and-hold (bit-crush), stutters, drop-outs and static."""
    import random
    rnd = random.Random(79)
    out = list(samples)
    # sample-and-hold with a wandering hold length, coarse levels
    i = 0
    while i < len(out):
        hold = rnd.choice((3, 5, 8, 13))
        v = round(out[i] * 10) / 10
        for k in range(i, min(len(out), i + hold)):
            out[k] = v
        i += hold
    # stutter: short pieces repeated; drop-outs: short silences
    for _ in range(7):
        start = rnd.randrange(0, len(out) - 9000)
        piece = out[start:start + rnd.randrange(600, 1800)]
        for rep in range(1, rnd.randrange(2, 5)):
            o = start + rep * len(piece)
            n = max(0, min(len(piece), len(out) - o))
            out[o:o + n] = piece[:n]
    for _ in range(5):
        start = rnd.randrange(2000, len(out) - 3000)
        for k in range(start, start + rnd.randrange(500, 2200)):
            out[k] = 0.0
    # static bursts and a low hum under everything
    for _ in range(6):
        start = rnd.randrange(0, len(out) - 3000)
        for k in range(start, start + rnd.randrange(400, 2500)):
            out[k] += rnd.uniform(-0.22, 0.22)
    for k in range(len(out)):
        out[k] = max(-0.9, min(0.9, out[k] * 1.3 + 0.05 * math.sin(2 * math.pi * 55 * k / RATE)))
    fade = int(RATE * 0.03)
    for k in range(fade):
        out[-1 - k] *= k / fade
    return out


def encode(samples, path):
    os.makedirs(os.path.dirname(path), exist_ok=True)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
        wav = tmp.name
    with wave.open(wav, "wb") as w:
        w.setnchannels(1)
        w.setsampwidth(2)
        w.setframerate(RATE)
        w.writeframes(b"".join(struct.pack("<h", int(max(-1.0, min(1.0, s)) * 32767)) for s in samples))
    # mono is what the README asks for; ffmpeg's built-in vorbis encoder only does stereo, libvorbis does mono
    encoders = subprocess.run(["ffmpeg", "-hide_banner", "-encoders"], capture_output=True, text=True).stdout
    codec = ["-c:a", "libvorbis", "-ac", "1"] if "libvorbis" in encoders else ["-c:a", "vorbis", "-strict", "-2", "-ac", "2"]
    subprocess.run(["ffmpeg", "-hide_banner", "-loglevel", "error", "-y", "-i", wav, *codec, "-ar", str(RATE), path], check=True)
    os.unlink(wav)


def sounds():
    d = os.path.join(PACK, "sounds")
    e5, a5 = 659.26, 880.0
    encode(mix([(0.0, bell(e5, 0.9)), (0.28, bell(a5, 1.1))], 1.4), os.path.join(d, "chime_open.ogg"))
    encode(mix([(0.0, bell(a5, 0.7, 0.4)), (0.22, bell(e5, 0.9, 0.4))], 1.15), os.path.join(d, "chime_close.ogg"))
    encode(mix([(0.0, bell(1318.5, 0.25, 0.35))], 0.3), os.path.join(d, "send_confirm.ogg"))
    encode(corrupt(mix([(0.0, bell(e5 * 0.94, 0.9)), (0.26, bell(a5 * 1.07, 1.0)), (0.62, bell(e5 * 0.5, 1.0, 0.45))], 1.7)),
           os.path.join(d, "chime_corrupt.ogg"))


if __name__ == "__main__":
    textures()
    if "--no-sounds" not in sys.argv:
        sounds()
    print("placeholders written")
