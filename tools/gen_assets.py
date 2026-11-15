#!/usr/bin/env python3
"""Generate the small text assets the app ships with.

  * ctc_vocab.txt  character vocabulary of the acoustic model (id = line number)
  * t2s.txt        traditional -> simplified map for lyric normalisation

The 600 MB acoustic model itself is NOT generated here; CI downloads it into
app/src/main/jniLibs/arm64-v8a/ where Android unpacks it as a real file.

    python tools/gen_assets.py --vocab tools/cn_vocab.json
"""
import argparse
import json
import os

HERE = os.path.dirname(os.path.abspath(__file__))
ROOT = os.path.dirname(HERE)
ASSETS = os.path.join(ROOT, "app", "src", "main", "assets")


def write_vocab(vocab_json):
    vocab = json.load(open(vocab_json, encoding="utf-8"))
    inv = {index: token for token, index in vocab.items()}
    ids = sorted(inv)
    if ids != list(range(len(ids))):
        raise SystemExit("vocab ids are not contiguous from 0")
    lines = [inv[i] for i in ids]
    with open(os.path.join(ASSETS, "ctc_vocab.txt"), "w", encoding="utf-8", newline="\n") as f:
        f.write("\n".join(lines) + "\n")
    print("ctc_vocab.txt: %d tokens" % len(lines))


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
    parser = argparse.ArgumentParser()
    parser.add_argument("--vocab", default=os.path.join(HERE, "cn_vocab.json"),
                        help="vocab.json of the CTC model")
    args = parser.parse_args()
    os.makedirs(ASSETS, exist_ok=True)
    write_vocab(args.vocab)
    write_t2s()


if __name__ == "__main__":
    main()
