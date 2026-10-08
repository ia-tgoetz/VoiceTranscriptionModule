"""Deterministic synthetic background noise (16 kHz mono PCM16LE) for noise-robustness tests."""
from __future__ import annotations

import math
import random
import struct

RATE = 16000


def _pcm(samples: list[float]) -> bytes:
    return b"".join(struct.pack("<h", max(-32768, min(32767, int(s * 32767)))) for s in samples)


def _scale(samples: list[float], dbfs: float) -> list[float]:
    rms = math.sqrt(sum(s * s for s in samples) / len(samples)) or 1.0
    gain = (10 ** (dbfs / 20)) / rms
    return [s * gain for s in samples]


def pink(seconds: float, dbfs: float = -35.0, seed: int = 1) -> bytes:
    """Pink-ish noise (Paul Kellet filter): HVAC / airflow / fan rush."""
    rnd = random.Random(seed)
    b0 = b1 = b2 = 0.0
    out = []
    for _ in range(int(seconds * RATE)):
        w = rnd.uniform(-1, 1)
        b0 = 0.99765 * b0 + w * 0.0990460
        b1 = 0.96300 * b1 + w * 0.2965164
        b2 = 0.57000 * b2 + w * 1.0526913
        out.append(b0 + b1 + b2 + w * 0.1848)
    return _pcm(_scale(out, dbfs))


def hum(seconds: float, dbfs: float = -35.0, seed: int = 2) -> bytes:
    """Mains hum + harmonics + a little hiss: motors, transformers."""
    rnd = random.Random(seed)
    out = []
    for i in range(int(seconds * RATE)):
        t = i / RATE
        s = sum(math.sin(2 * math.pi * 60 * k * t) / k for k in (1, 2, 3, 5))
        out.append(s + rnd.uniform(-0.15, 0.15))
    return _pcm(_scale(out, dbfs))


def clicks(seconds: float, dbfs: float = -30.0, seed: int = 3) -> bytes:
    """Keyboard / machinery clicks: short decaying bursts over faint hiss."""
    rnd = random.Random(seed)
    out = [rnd.uniform(-0.01, 0.01) for _ in range(int(seconds * RATE))]
    t = 0
    while t < len(out):
        t += rnd.randint(1200, 6000)
        for j in range(min(400, len(out) - t)):
            out[t + j] += rnd.uniform(-1, 1) * math.exp(-j / 60)
    return _pcm(_scale(out, dbfs))


def mix(speech: bytes, noise: bytes) -> bytes:
    """Sum two PCM16 buffers (noise looped/truncated to the speech length)."""
    n = len(speech) // 2
    a = struct.unpack(f"<{n}h", speech)
    m = len(noise) // 2
    b = struct.unpack(f"<{m}h", noise)
    return b"".join(struct.pack("<h", max(-32768, min(32767, a[i] + b[i % m]))) for i in range(n))
