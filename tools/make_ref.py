#!/usr/bin/env python3
"""Dump reference data for the JVM verification harness (tools/verify/JvmCheck.kt).

    python tools/make_ref.py C:/tmp/at-models C:/tmp/zh_corpus.wav C:/tmp/zh_corpus.json C:/tmp/at-verify

Everything is written as plain text / little endian float32 so it can be read from
Kotlin without a JSON dependency.
"""
import json
import os
import struct
import sys

import numpy as np

sys.path.insert(0, os.path.dirname(os.path.abspath(__file__)))
import ref_pipeline as R  # noqa: E402


def write_floats(path, array):
    with open(path, "wb") as f:
        f.write(struct.pack("<%df" % array.size, *array.reshape(-1).tolist()))


def main():
    model_dir, audio_path, corpus_path, out_dir = sys.argv[1:5]
    os.makedirs(out_dir, exist_ok=True)

    audio = R.load_audio(audio_path)
    duration = len(audio) / R.SR
    write_floats(os.path.join(out_dir, "audio.f32"), audio)

    mel_filters = np.fromfile(os.path.join(R.ASSETS, "mel_filters.bin"),
                              dtype=np.float32).reshape(R.N_MEL, R.N_FFT // 2 + 1)
    mel = R.log_mel(audio, mel_filters, 0.0)[0]
    write_floats(os.path.join(out_dir, "mel.f32"), mel)

    t2s = R.load_t2s()
    model = R.Model(model_dir)
    regions = R.vad(audio)
    windows = R.build_windows(regions, duration)
    texts = []
    for (ws, we) in windows:
        hidden = model.enc.run(None, {"input_features": R.log_mel(audio, mel_filters, ws)})[0]
        texts.append(model.transcribe(hidden, 50260))

    with open(os.path.join(out_dir, "regions.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for s, e in regions:
            f.write("%.6f\t%.6f\n" % (s, e))
    with open(os.path.join(out_dir, "windows.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for s, e in windows:
            f.write("%.6f\t%.6f\n" % (s, e))
    with open(os.path.join(out_dir, "texts.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(texts) + "\n")

    chars = R.speech_positions(texts, windows, regions, duration)
    with open(os.path.join(out_dir, "chars.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for ch, t0, t1 in chars:
            f.write("%d\t%.6f\t%.6f\n" % (ord(ch), t0, t1))

    corpus = json.load(open(corpus_path, encoding="utf-8"))
    spans, hits, total = R.align(corpus["lines"], chars, t2s, regions, duration)
    with open(os.path.join(out_dir, "lines.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(corpus["lines"]) + "\n")
    with open(os.path.join(out_dir, "spans.tsv"), "w", encoding="utf-8", newline="\n") as f:
        for s, e in spans:
            f.write("%.6f\t%.6f\n" % (s, e))
    with open(os.path.join(out_dir, "meta.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("duration=%.6f\nmatched=%d\ntotal=%d\n" % (duration, hits, total))

    # tokenizer probe: decode a few ids on their own so the Kotlin detokeniser can be checked
    vocab = R.load_vocab()
    probe = []
    for tid in list(range(1000, 1020)) + list(range(20000, 20010)):
        raw = vocab.get(tid, b"")
        probe.append("%d\t%s" % (tid, raw.decode("utf-8", "replace")))
    with open(os.path.join(out_dir, "tokens.tsv"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(probe) + "\n")

    print("wrote reference data to", out_dir)
    print("texts:", texts)
    print("chars:", len(chars), "spans:", len(spans))


if __name__ == "__main__":
    main()
