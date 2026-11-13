#!/usr/bin/env python3
"""Generate the binary assets used by the Android app.

Outputs (written into app/src/main/assets/):
  * mel_filters.bin      80 x 201 float32 mel filter bank (Whisper / librosa Slaney)
  * byte_table.txt       GPT-2 byte-level BPE alphabet -> raw byte value
  * vocab.txt            whisper vocabulary, one byte-level token per line (id = line no.)
  * suppress_tokens.txt  generation config suppression list
  * t2s.txt              traditional -> simplified character map for matching

The mel filter bank must match `librosa.filters.mel(sr=16000, n_fft=400, n_mels=80)`
which is what OpenAI's Whisper uses.  The implementation below is a faithful port of
librosa's slaney scale so it does not need librosa installed, but the script verifies
itself against librosa when available.
"""
import json
import os
import struct
import sys

import numpy as np

SR = 16000
N_FFT = 400
N_MELS = 80
F_MIN = 0.0
F_MAX = SR / 2.0

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


def hz_to_mel(freq):
    freq = np.asarray(freq, dtype=np.float64)
    f_min, f_sp = 0.0, 200.0 / 3
    mels = (freq - f_min) / f_sp
    min_log_hz = 1000.0
    min_log_mel = (min_log_hz - f_min) / f_sp
    logstep = np.log(6.4) / 27.0
    if freq.ndim:
        mels = np.where(freq >= min_log_hz,
                        min_log_mel + np.log(np.maximum(freq, 1e-12) / min_log_hz) / logstep,
                        mels)
    elif freq >= min_log_hz:
        mels = min_log_mel + np.log(max(freq, 1e-12) / min_log_hz) / logstep
    return mels


def mel_to_hz(mels):
    mels = np.asarray(mels, dtype=np.float64)
    f_min, f_sp = 0.0, 200.0 / 3
    freqs = f_min + f_sp * mels
    min_log_hz = 1000.0
    min_log_mel = (min_log_hz - f_min) / f_sp
    logstep = np.log(6.4) / 27.0
    if mels.ndim:
        freqs = np.where(mels >= min_log_mel,
                         min_log_hz * np.exp(logstep * (mels - min_log_mel)),
                         freqs)
    elif mels >= min_log_mel:
        freqs = min_log_hz * np.exp(logstep * (mels - min_log_mel))
    return freqs


def mel_frequencies(n_mels, fmin, fmax):
    min_mel = hz_to_mel(np.array([fmin]))[0]
    max_mel = hz_to_mel(np.array([fmax]))[0]
    return mel_to_hz(np.linspace(min_mel, max_mel, n_mels))


def mel_filterbank():
    weights = np.zeros((N_MELS, 1 + N_FFT // 2), dtype=np.float64)
    fftfreqs = np.linspace(0, SR / 2.0, 1 + N_FFT // 2)
    mel_f = mel_frequencies(N_MELS + 2, F_MIN, F_MAX)
    fdiff = np.diff(mel_f)
    ramps = np.subtract.outer(mel_f, fftfreqs)
    for i in range(N_MELS):
        lower = -ramps[i] / fdiff[i]
        upper = ramps[i + 2] / fdiff[i + 1]
        weights[i] = np.maximum(0.0, np.minimum(lower, upper))
    enorm = 2.0 / (mel_f[2:N_MELS + 2] - mel_f[:N_MELS])
    weights *= enorm[:, np.newaxis]
    return weights.astype(np.float32)


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


def write_mel():
    fb = mel_filterbank()
    try:
        import librosa
        ref = librosa.filters.mel(sr=SR, n_fft=N_FFT, n_mels=N_MELS).astype(np.float32)
        diff = float(np.abs(ref - fb).max())
        print("mel: max abs diff vs librosa = %.3e" % diff)
        if diff > 1e-6:
            print("!! mel filter bank does not match librosa", file=sys.stderr)
            return False
    except ImportError:
        print("mel: librosa not installed, skipping cross-check")

    with open(os.path.join(ASSETS, "mel_filters.bin"), "wb") as f:
        f.write(struct.pack("<%df" % fb.size, *fb.reshape(-1).tolist()))
    return True


def write_byte_table():
    table = bytes_to_unicode()
    lines = ["%d %d" % (ord(ch), b) for ch, b in sorted(table.items(), key=lambda kv: kv[1])]
    with open(os.path.join(ASSETS, "byte_table.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("byte_table.txt: %d entries" % len(lines))


def write_vocab():
    vocab = json.load(open(os.path.join(HERE, "vocab.json"), encoding="utf-8"))
    ordered = sorted(vocab.items(), key=lambda kv: kv[1])
    ids = [i for _, i in ordered]
    if ids != list(range(len(ids))):
        raise SystemExit("vocab ids are not contiguous from 0")
    with open(os.path.join(ASSETS, "vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(tok for tok, _ in ordered) + "\n")
    print("vocab.txt: %d tokens" % len(ordered))


def write_suppress():
    cfg = json.load(open(os.path.join(HERE, "whisper_generation_config.json")))
    tokens = sorted(set(int(t) for t in cfg["suppress_tokens"]) | {50363})
    with open(os.path.join(ASSETS, "suppress_tokens.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(str(t) for t in tokens) + "\n")
    print("suppress_tokens.txt: %d ids" % len(tokens))


def write_t2s():
    pairs, seen = [], set()
    for line in open(os.path.join(HERE, "TSCharacters.txt"), encoding="utf-8"):
        if line.startswith("#"):
            continue
        parts = line.split()
        if len(parts) < 2:
            continue
        t, s = parts[0], parts[1]
        if len(t) != 1 or len(s) != 1 or t in seen or t == s:
            continue
        seen.add(t)
        pairs.append("%s %s" % (t, s))
    with open(os.path.join(ASSETS, "t2s.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(pairs) + "\n")
    print("t2s.txt: %d pairs" % len(pairs))


def main():
    os.makedirs(ASSETS, exist_ok=True)
    if not write_mel():
        return 1
    write_byte_table()
    write_vocab()
    write_suppress()
    write_t2s()
    return 0


if __name__ == "__main__":
    sys.exit(main())
