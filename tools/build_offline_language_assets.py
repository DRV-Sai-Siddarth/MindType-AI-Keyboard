#!/usr/bin/env python3
"""Build offline English prediction assets from public source datasets.

This script is intentionally a development/build tool, never shipped or invoked by
the Android app. It produces GZIP assets consumed entirely on-device at runtime.

Sources:
  - Hermit Dave FrequencyWords (CC-BY-SA 4.0): English word frequencies
  - Peter Norvig Google Books Ngram extract: word-pair counts
  - Unicode CLDR annotations (Unicode License): English emoji keywords
"""
from __future__ import annotations

import gzip
import heapq
import json
import re
import shutil
import sys
import tempfile
import urllib.request
from collections import defaultdict
from pathlib import Path


WORD_FREQUENCIES_URL = (
    "https://raw.githubusercontent.com/hermitdave/FrequencyWords/master/"
    "content/2018/en/en_full.txt"
)
BIGRAMS_URL = "https://norvig.com/ngrams/count_2w.txt"
CLDR_ANNOTATIONS_URL = (
    "https://raw.githubusercontent.com/unicode-org/cldr-json/main/cldr-json/"
    "cldr-annotations-full/annotations/en/annotations.json"
)

ROOT = Path(__file__).resolve().parents[1]
OUTPUT = ROOT / "app" / "src" / "main" / "assets" / "language"
MAX_WORDS = 200_000
MAX_CONTEXTS = 100_000
MAX_NEXT_PER_CONTEXT = 12
MAX_EMOJIS_PER_KEYWORD = 8
WORD_RE = re.compile(r"^[^\W_][\w'’-]{0,63}$", re.UNICODE)
KEYWORD_RE = re.compile(r"[^\W_][\w'’-]{0,63}", re.UNICODE)


def download(url: str, destination: Path) -> None:
    request = urllib.request.Request(url, headers={"User-Agent": "MindTypeAssetBuilder/1.0"})
    with urllib.request.urlopen(request, timeout=120) as response, destination.open("wb") as output:
        shutil.copyfileobj(response, output)


def canonical_word(value: str) -> str | None:
    word = value.strip().lower()
    return word if WORD_RE.fullmatch(word) else None


def write_gzip_tsv(path: Path, header: str, rows) -> None:
    with gzip.open(path, "wt", encoding="utf-8", newline="\n") as output:
        output.write(f"# {header}\n")
        for row in rows:
            output.write("\t".join(map(str, row)) + "\n")


def build_dictionary(source: Path) -> set[str]:
    words: dict[str, int] = {}
    with source.open(encoding="utf-8") as input_file:
        for line in input_file:
            parts = line.rstrip().rsplit(None, 1)
            if len(parts) != 2:
                continue
            word = canonical_word(parts[0])
            try:
                frequency = int(parts[1])
            except ValueError:
                continue
            if word and frequency > 0:
                words[word] = max(words.get(word, 0), min(frequency, 2_147_483_647))
    ranked = sorted(words.items(), key=lambda item: (-item[1], item[0]))[:MAX_WORDS]
    write_gzip_tsv(OUTPUT / "dictionary.tsv.gz", "word<TAB>frequency", ranked)
    return {word for word, _ in ranked}


def build_bigrams(source: Path, dictionary: set[str]) -> None:
    # Heap per first word avoids retaining the multi-gigabyte source in memory.
    candidates: dict[str, list[tuple[int, str]]] = defaultdict(list)
    with source.open(encoding="utf-8", errors="ignore") as input_file:
        for line in input_file:
            parts = line.rstrip().rsplit(None, 1)
            if len(parts) != 2:
                continue
            pair = parts[0].split()
            if len(pair) != 2:
                continue
            first, second = canonical_word(pair[0]), canonical_word(pair[1])
            if first not in dictionary or second not in dictionary:
                continue
            try:
                frequency = int(parts[1])
            except ValueError:
                continue
            if frequency <= 0:
                continue
            heap = candidates[first]
            item = (frequency, second)
            if len(heap) < MAX_NEXT_PER_CONTEXT:
                heapq.heappush(heap, item)
            elif item > heap[0]:
                heapq.heapreplace(heap, item)

    ranked_contexts = sorted(
        candidates.items(), key=lambda item: -sum(count for count, _ in item[1])
    )[:MAX_CONTEXTS]
    rows = (
        (first, second, count)
        for first, heap in ranked_contexts
        for count, second in sorted(heap, key=lambda item: (-item[0], item[1]))
    )
    write_gzip_tsv(OUTPUT / "bigrams.tsv.gz", "previous<TAB>next<TAB>frequency", rows)


def build_emojis(source: Path) -> None:
    document = json.loads(source.read_text(encoding="utf-8"))
    annotations = document["annotations"]["annotations"]
    by_keyword: dict[str, list[str]] = defaultdict(list)
    for emoji, metadata in annotations.items():
        for label in metadata.get("default", []):
            # Index every individual CLDR tag word, so "red heart" is reachable by
            # either "red" or "heart" while retaining the standard Unicode emoji.
            for token in KEYWORD_RE.findall(label):
                keyword = canonical_word(token)
                if keyword and emoji not in by_keyword[keyword] and len(by_keyword[keyword]) < MAX_EMOJIS_PER_KEYWORD:
                    by_keyword[keyword].append(emoji)
    (OUTPUT / "emojis.json").write_text(
        json.dumps(dict(sorted(by_keyword.items())), ensure_ascii=False, separators=(",", ":")),
        encoding="utf-8",
    )


def main() -> int:
    OUTPUT.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="mindtype-language-") as temporary:
        temp = Path(temporary)
        frequencies, bigrams, annotations = temp / "frequencies.txt", temp / "bigrams.txt", temp / "annotations.json"
        print("Downloading word frequencies…")
        download(WORD_FREQUENCIES_URL, frequencies)
        print("Downloading bigram counts (this source is large)…")
        download(BIGRAMS_URL, bigrams)
        print("Downloading Unicode CLDR emoji annotations…")
        download(CLDR_ANNOTATIONS_URL, annotations)
        dictionary = build_dictionary(frequencies)
        build_bigrams(bigrams, dictionary)
        # This public source provides bigrams. The engine gracefully falls back to
        # them until a trigram corpus is selected for a future locale build.
        write_gzip_tsv(OUTPUT / "trigrams.tsv.gz", "first<TAB>second<TAB>next<TAB>frequency", [])
        build_emojis(annotations)
    print(f"Offline assets written to {OUTPUT}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
