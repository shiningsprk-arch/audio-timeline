#!/usr/bin/env python3
"""Reference implementation of the audio -> text-timeline alignment pipeline.

Every step here is mirrored by the Kotlin code under app/src/main/java/com/shiningsprk/audiotimeline,
so the algorithm can be validated and its accuracy measured on a machine with Python.

    python tools/ref_pipeline.py C:/tmp/at-models C:/tmp/zh_corpus.wav C:/tmp/zh_corpus.json
"""
import json
import os
import sys

import librosa
import numpy as np
import onnxruntime as ort

SR = 16000
N_FFT = 400
HOP = 160
N_MEL = 80
N_FRAMES = 3000
CHUNK = 480000
WIN_SEC = 30.0
MAX_TOKENS = 448
SOT = 50258
EOT = 50257
TRANSCRIBE = 50359
NO_TS = 50363
TS_BASE = 50364

VAD_FRAME = 400
VAD_HOP = 160
VAD_MIN_GAP = 0.30
VAD_MIN_SPEECH = 0.12

HERE = os.path.dirname(os.path.abspath(__file__))
ASSETS = os.path.join(os.path.dirname(HERE), "app", "src", "main", "assets")


# ---------------------------------------------------------------- assets
def bytes_to_unicode():
    bs = list(range(ord("!"), ord("~") + 1)) + list(range(0xA1, 0xAC + 1)) + list(range(0xAE, 0xFF + 1))
    cs = bs[:]
    n = 0
    for b in range(2 ** 8):
        if b not in bs:
            bs.append(b)
            cs.append(2 ** 8 + n)
            n += 1
    return {chr(c): b for b, c in zip(bs, cs)}


def load_vocab():
    rev = {c: b for c, b in bytes_to_unicode().items()}
    out = {}
    with open(os.path.join(ASSETS, "vocab.txt"), encoding="utf-8") as f:
        for i, line in enumerate(f.read().splitlines()):
            if line == "":
                continue
            out[i] = bytes(rev[c] for c in line)
    return out


def load_t2s():
    table = {}
    for line in open(os.path.join(ASSETS, "t2s.txt"), encoding="utf-8"):
        parts = line.split()
        if len(parts) >= 2:
            table[parts[0]] = parts[1]
    return table


# ---------------------------------------------------------------- audio
def load_audio(path):
    y, _ = librosa.load(path, sr=SR, mono=True)
    return y.astype(np.float32)


def vad(audio):
    n = 1 + max(0, (len(audio) - VAD_FRAME) // VAD_HOP)
    if n <= 0:
        return []
    idx = np.arange(VAD_FRAME)[None, :] + VAD_HOP * np.arange(n)[:, None]
    frames = audio[idx]
    db = 20 * np.log10(np.sqrt((frames ** 2).mean(1)) + 1e-8)
    noise = float(np.percentile(db, 10))
    peak = float(np.percentile(db, 99))
    thr = max(noise + 10.0, peak - 38.0)
    mask = db > thr

    runs, start = [], None
    for i, v in enumerate(mask):
        if v and start is None:
            start = i
        elif not v and start is not None:
            runs.append((start, i))
            start = None
    if start is not None:
        runs.append((start, len(mask)))

    merged = []
    for a, b in runs:
        if merged and (a - merged[-1][1]) * VAD_HOP / SR < VAD_MIN_GAP:
            merged[-1] = (merged[-1][0], b)
        else:
            merged.append((a, b))

    pad = int(0.05 * SR)
    regions = []
    for a, b in merged:
        if (b - a) * VAD_HOP / SR < VAD_MIN_SPEECH:
            continue
        regions.append((max(0.0, (a * VAD_HOP - pad) / SR),
                        min(len(audio) / SR, (b * VAD_HOP + pad) / SR)))
    return regions


def build_windows(regions, duration):
    """Group speech regions into <=30s windows so no window cuts through speech."""
    windows, cur = [], None
    for (s, e) in regions:
        s, e = max(0.0, s - 0.15), min(duration, e + 0.15)
        while e - s > WIN_SEC:
            if cur:
                windows.append(tuple(cur))
                cur = None
            windows.append((s, s + WIN_SEC))
            s += WIN_SEC
        if cur is None:
            cur = [s, e]
        elif e - cur[0] <= WIN_SEC:
            cur[1] = e
        else:
            windows.append(tuple(cur))
            cur = [s, e]
    if cur:
        windows.append(tuple(cur))
    if not windows:
        windows = []
    covered = sum(e - s for (s, e) in windows)
    if not windows or covered < duration * 0.6:
        # VAD is not trustworthy here (music, noise, one loud click): tile the whole file
        windows, t = [], 0.0
        while t < duration:
            windows.append((t, min(duration, t + WIN_SEC)))
            t += WIN_SEC
    return windows


def log_mel(audio, mel_filters, start_sec):
    seg = np.zeros(CHUNK, dtype=np.float32)
    off = int(start_sec * SR)
    src = audio[off:off + CHUNK]
    seg[:len(src)] = src
    window = np.hanning(N_FFT + 1)[:-1].astype(np.float32)
    padded = np.pad(seg, (N_FFT // 2, N_FFT // 2), mode="reflect")
    frames = np.lib.stride_tricks.sliding_window_view(padded, N_FFT)[::HOP][:N_FRAMES]
    spec = np.fft.rfft(frames * window, axis=-1)
    power = (np.abs(spec) ** 2).T
    mel = mel_filters @ power
    log_spec = np.log10(np.maximum(mel, 1e-10))
    log_spec = np.maximum(log_spec, log_spec.max() - 8.0)
    return ((log_spec + 4.0) / 4.0).astype(np.float32)[None]


# ---------------------------------------------------------------- asr
class Model:
    def __init__(self, model_dir):
        so = ort.SessionOptions()
        so.intra_op_num_threads = 4
        self.enc = ort.InferenceSession(os.path.join(model_dir, "encoder_model_quantized.onnx"), so)
        self.dec = ort.InferenceSession(os.path.join(model_dir, "decoder_model_quantized.onnx"), so)
        self.decp = ort.InferenceSession(os.path.join(model_dir, "decoder_with_past_model_quantized.onnx"), so)
        cfg = json.load(open(os.path.join(HERE, "whisper_generation_config.json")))
        self.suppress = sorted(set(cfg["suppress_tokens"]) | {NO_TS})
        self.id2bytes = load_vocab()

    def detect_language(self, hidden):
        out = self.dec.run(None, {"input_ids": np.array([[SOT]], dtype=np.int64),
                                  "encoder_hidden_states": hidden})
        return int(np.argmax(out[0][0, -1, 50259:50358])) + 50259

    def transcribe(self, hidden, lang_token):
        """Greedy decode without timestamps (same as the Kotlin engine)."""
        prompt = [SOT, lang_token, TRANSCRIBE, NO_TS]
        outs = self.dec.run(None, {"input_ids": np.array([prompt], dtype=np.int64),
                                   "encoder_hidden_states": hidden})
        logits = outs[0]
        enc_past = {o.name.replace("present.", "past_key_values."): v
                    for o, v in zip(self.dec.get_outputs()[1:], outs[1:]) if "encoder" in o.name}
        dec_past = {o.name.replace("present.", "past_key_values."): v
                    for o, v in zip(self.dec.get_outputs()[1:], outs[1:]) if "decoder" in o.name}
        out_names = [o.name.replace("present.", "past_key_values.") for o in self.decp.get_outputs()[1:]]

        byte_buf = bytearray()
        max_new = max(1, 448 - len(prompt))
        for step in range(max_new):
            scores = logits[0, -1].astype(np.float64).copy()
            scores[self.suppress] = -1e9
            scores[TS_BASE:] = -1e9
            if step == 0:
                scores[220] = -1e9
            nxt = int(np.argmax(scores))
            if nxt == EOT:
                break
            byte_buf += self.id2bytes.get(nxt, b"")
            try:
                outs = self.decp.run(None, {"input_ids": np.array([[nxt]], dtype=np.int64), **dec_past, **enc_past})
            except Exception:
                break
            logits = outs[0]
            dec_past = {n: v for n, v in zip(out_names, outs[1:])}
        return byte_buf.decode("utf-8", "replace")


def speech_positions(texts, windows, regions, duration):
    """Map every recognised char to (t0, t1), skipping silence inside each window."""
    out = []
    for (text, (ws, we)) in zip(texts, windows):
        local = [(max(ws, s), min(we, e)) for (s, e) in regions if e > ws and s < we]
        if not local:
            local = [(ws, we)]
        total = sum(e - s for (s, e) in local)
        weights = [1.0 if ch.isalnum() else 0.08 for ch in text]
        wsum = max(1e-6, sum(weights))
        cum, acc = [], 0.0
        for (s, e) in local:
            cum.append((acc, acc + (e - s), s))
            acc += e - s
        acc = 0.0
        for ch, w in zip(text, weights):
            t0 = acc_pos(cum, total * acc / wsum)
            acc += w
            t1 = acc_pos(cum, total * acc / wsum)
            # a character never spans a silence gap: clamp its end to the region it starts in
            for (a, b, rs) in cum:
                if t0 <= rs + (b - a) + 1e-6:
                    t1 = min(t1, rs + (b - a))
                    break
            out.append((ch, min(t0, t1), max(t0, t1)))
    return out


def acc_pos(cum, target):
    for (a, b, s) in cum:
        if target <= b:
            return s + max(0.0, target - a)
    a, b, s = cum[-1]
    return s + (b - a)


# ---------------------------------------------------------------- alignment
def norm(ch, t2s):
    ch = t2s.get(ch, ch).lower()
    return ch if ch.isalnum() else None


def align(lines, chars, t2s, regions=(), duration=0.0):
    uchars = []
    for li, line in enumerate(lines):
        for ch in line:
            k = norm(ch, t2s)
            if k is not None:
                uchars.append((k, li))
    rchars = [(norm(c, t2s), t0, t1) for (c, t0, t1) in chars if norm(c, t2s) is not None]
    n, m = len(uchars), len(rchars)
    if n == 0 or m == 0:
        raise ValueError("nothing to align")

    prev = [0] + [-j for j in range(1, m + 1)]
    bt = np.zeros((n + 1, m + 1), dtype=np.int8)
    for i in range(1, n + 1):
        cur = [prev[0] - 1] + [0] * m
        bt[i, 0] = 1
        ui = uchars[i - 1][0]
        for j in range(1, m + 1):
            diag = prev[j - 1] + (2 if ui == rchars[j - 1][0] else -1)
            up = prev[j] - 1
            left = cur[j - 1] - 1
            best, op = diag, 0
            if up > best:
                best, op = up, 1
            if left > best:
                best, op = left, 2
            cur[j] = best
            bt[i, j] = op
        prev = cur

    i, j = n, m
    pairs = []
    while i > 0 and j > 0:
        op = bt[i, j]
        if op == 0:
            pairs.append((i - 1, j - 1))
            i, j = i - 1, j - 1
        elif op == 1:
            i -= 1
        else:
            j -= 1
    pairs.reverse()

    per_line, hits = {}, 0
    for a, b in pairs:
        if uchars[a][0] == rchars[b][0]:
            hits += 1
            li = uchars[a][1]
            t0, t1 = rchars[b][1], rchars[b][2]
            lo, hi = per_line.get(li, (t0, t1))
            per_line[li] = (min(lo, t0), max(hi, t1))

    spans = [list(per_line[li]) if li in per_line else None for li in range(len(lines))]
    for li in range(len(lines)):
        if spans[li] is None:
            prev_end = max([spans[k][1] for k in range(li) if spans[k]] or [0.0])
            nxt = [spans[k][0] for k in range(li + 1, len(lines)) if spans[k]]
            hi = nxt[0] - 0.02 if nxt else prev_end + 0.8
            lo = prev_end + 0.02
            spans[li] = [lo, max(lo + 0.25, hi)]
    # a new line almost always starts where speech (re)starts: snap to the nearest onset
    edges = sorted({round(r[0], 3) for r in regions})
    for li in range(1, len(spans)):
        t = spans[li][0]
        near = min(edges, key=lambda c: abs(c - t)) if edges else None
        if near is not None and abs(near - t) <= 1.0:
            spans[li][0] = near
        spans[li][0] = max(spans[li][0], spans[li - 1][0] + 0.05)
        spans[li - 1][1] = min(spans[li - 1][1], spans[li][0] - 0.01)
    # minimum line duration, but never past the next line's start
    for li in range(len(spans)):
        min_end = spans[li][0] + 0.2
        cap = spans[li + 1][0] - 0.01 if li + 1 < len(spans) else float("inf")
        spans[li][1] = min(max(spans[li][1], min_end), max(min_end, cap))
    # keep everything inside the audio
    if duration > 0:
        for s in spans:
            s[0] = min(max(0.0, s[0]), max(0.0, duration - 0.2))
            s[1] = min(max(s[1], s[0] + 0.2), max(s[0] + 0.2, duration))
    return spans, hits, n


# ---------------------------------------------------------------- output
def fmt_srt_time(t):
    ms = int(round(t * 1000))
    return "%02d:%02d:%02d,%03d" % (ms // 3600000, ms // 60000 % 60, ms // 1000 % 60, ms % 1000)


def to_srt(lines, spans):
    out = []
    for i, (line, (a, b)) in enumerate(zip(lines, spans), 1):
        out.append("%d\n%s --> %s\n%s\n" % (i, fmt_srt_time(a), fmt_srt_time(b), line))
    return "\n".join(out)


def main():
    model_dir, audio_path = sys.argv[1], sys.argv[2]
    corpus = json.load(open(sys.argv[3], encoding="utf-8")) if len(sys.argv) > 3 else None
    audio = load_audio(audio_path)
    duration = len(audio) / SR
    t2s = load_t2s()
    model = Model(model_dir)
    mel_filters = np.fromfile(os.path.join(ASSETS, "mel_filters.bin"), dtype=np.float32).reshape(N_MEL, N_FFT // 2 + 1)

    regions = vad(audio)
    windows = build_windows(regions, duration)
    print("duration %.2fs, %d speech regions, %d windows" % (duration, len(regions), len(windows)))

    texts = []
    for (ws, we) in windows:
        hidden = model.enc.run(None, {"input_features": log_mel(audio, mel_filters, ws)})[0]
        lang = model.detect_language(hidden)
        txt = model.transcribe(hidden, lang)
        texts.append(txt)
        print("  window %.2f-%.2f lang=%d  %s" % (ws, we, lang, txt))

    chars = speech_positions(texts, windows, regions, duration)
    print("chars", len(chars))
    if not corpus:
        return
    spans, hits, total = align(corpus["lines"], chars, t2s, regions, duration)
    print("match %d/%d = %.0f%%" % (hits, total, 100.0 * hits / total))
    err = []
    for line, span, truth in zip(corpus["lines"], spans, corpus["bounds"]):
        e0, e1 = span[0] - truth[0], span[1] - truth[1]
        err += [abs(e0), abs(e1)]
        print("  truth %6.2f-%6.2f | got %6.2f-%6.2f | err %+.2f/%+.2f | %s" %
              (truth[0], truth[1], span[0], span[1], e0, e1, line))
    print("MAE %.3fs  max %.3fs" % (float(np.mean(err)), float(np.max(np.abs(err)))))


if __name__ == "__main__":
    main()
