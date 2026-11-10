"""조각 번호 매기기와 저장. 번호는 한 번 주면 바뀌지 않고, 지워진 번호는 다시 쓰지 않는다."""
import hashlib
import json
import os
from collections import defaultdict, deque
from pathlib import Path

import numpy as np

CHUNKS = "chunks.jsonl"
MANIFEST = "manifest.json"
VECTORS = "vectors.npy"
ITEMS = "items.json"


def text_hash(text: str) -> str:
    return hashlib.sha256(text.encode("utf-8")).hexdigest()


def assign_numbers(new_chunks, prev_chunks, next_no: int):
    """같은 문서의 같은 내용이면 이전 번호를 유지한다. 새 조각은 next_no 부터 받는다.

    반환: (번호가 붙은 조각 목록, 다음 번호, 새 번호 목록, 사라진 번호 목록)
    """
    prev = defaultdict(deque)
    for c in prev_chunks:
        prev[(c["path"], c["hash"])].append(c["no"])
    next_no = max([next_no] + [c["no"] + 1 for c in prev_chunks])
    out, added = [], []
    for c in new_chunks:
        h = text_hash(c["text"])
        queue = prev.get((c["path"], h))
        if queue:
            no = queue.popleft()
        else:
            no = next_no
            next_no += 1
            added.append(no)
        out.append({"no": no, "path": c["path"], "heading": c["heading"], "text": c["text"], "hash": h})
    removed = sorted(no for q in prev.values() for no in q)
    return out, next_no, added, removed


def _atomic_write(path: Path, write):
    tmp = path.with_name(path.name + ".tmp")
    write(tmp)
    os.replace(tmp, path)


def save(dir_: Path, chunks, vectors: np.ndarray, manifest: dict):
    """벡터 행 순서는 chunks.jsonl 줄 순서와 같다. manifest 를 마지막에 써서 읽는 쪽이 반쯤 쓴 상태를 보지 않게 한다."""
    if len(chunks) != len(vectors):
        raise ValueError(f"조각 {len(chunks)}개와 벡터 {len(vectors)}개의 수가 다르다")
    dir_.mkdir(parents=True, exist_ok=True)

    def write_chunks(p):
        with open(p, "w", encoding="utf-8") as f:
            for c in chunks:
                f.write(json.dumps(c, ensure_ascii=False) + "\n")

    def write_vectors(p):
        with open(p, "wb") as f:
            np.save(f, np.asarray(vectors, dtype=np.float32))

    _atomic_write(dir_ / CHUNKS, write_chunks)
    _atomic_write(dir_ / VECTORS, write_vectors)
    _atomic_write(dir_ / MANIFEST, lambda p: p.write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2), encoding="utf-8"))


def load_chunks(dir_: Path):
    path = dir_ / CHUNKS
    if not path.exists():
        return []
    with open(path, encoding="utf-8") as f:
        return [json.loads(line) for line in f if line.strip()]


def load_manifest(dir_: Path) -> dict:
    path = dir_ / MANIFEST
    return json.loads(path.read_text(encoding="utf-8")) if path.exists() else {}


def load(dir_: Path):
    """(조각 목록, 벡터, manifest). 아직 만든 적이 없으면 ([], None, {})."""
    chunks, manifest = load_chunks(dir_), load_manifest(dir_)
    if not chunks or not manifest:
        return [], None, {}
    vectors = np.load(dir_ / VECTORS)
    if len(vectors) != len(chunks):
        raise ValueError(f"{dir_}: 조각 {len(chunks)}개와 벡터 {len(vectors)}개의 수가 다르다 — 다시 만들어야 한다")
    return chunks, vectors, manifest
