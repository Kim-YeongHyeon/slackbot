"""소스 하나의 문서를 받아 조각·번호·임베딩까지 만들어 저장한다.

  python -m app.build <소스> [--from-dir DIR] [--ref REF] [--dry-run]
"""
import argparse
import datetime
import json
import os
import sys
import urllib.request
from pathlib import Path

from . import corpus, fetch
from .chunker import chunk_document
from .config import data_dir, load_settings
from .embedder import Embedder, EmbeddingCache, embed_with_cache
from .redact import redact
from .tokenizer import LABEL as TOKENIZER_LABEL


def make_chunks(docs: dict):
    """({path, heading, text} 목록, 가린 곳 수). 문서 경로 순서, 문서 안에서는 나온 순서."""
    chunks, redacted = [], 0
    for path in sorted(docs):
        for c in chunk_document(path, docs[path]):
            c["text"], n = redact(c["text"])
            redacted += n
            chunks.append(c)
    return chunks, redacted


def build(source, model: str, docs: dict, commit: str, embedder, cache, dry_run=False, progress=None, now=None):
    if not docs:
        raise SystemExit(f"{source.name}: 조건에 맞는 문서가 하나도 없다 — include/exclude 를 확인해 달라 (기존 데이터는 그대로 둔다)")
    raw, redacted = make_chunks(docs)
    prev_chunks = corpus.load_chunks(source.dir)
    prev_manifest = corpus.load_manifest(source.dir)
    chunks, next_no, added, removed = corpus.assign_numbers(raw, prev_chunks, prev_manifest.get("next_no", 1))
    summary = {
        "source": source.name, "files": len(docs), "chunks": len(chunks), "chars": sum(len(c["text"]) for c in chunks),
        "added": len(added), "removed": len(removed), "kept": len(chunks) - len(added), "redacted": redacted,
        "to_embed": len({c["hash"] for c in chunks if cache.get(c["hash"]) is None}),
    }
    if dry_run:
        return summary
    vectors, embedded = embed_with_cache(chunks, embedder, cache, progress=progress)
    manifest = {
        "source": source.name, "repo": source.repo, "ref": source.ref, "commit": commit,
        "model": model, "dim": int(vectors.shape[1]), "tokenizer": TOKENIZER_LABEL,
        "backend": source.backend, "index": source.index, "mode": source.mode,
        "built_at": (now or datetime.datetime.now(datetime.timezone.utc)).isoformat(timespec="seconds"),
        "files": len(docs), "chunks": len(chunks), "next_no": next_no, "redacted": redacted,
        "removed_nos": removed,
    }
    corpus.save(source.dir, chunks, vectors, manifest)
    summary["embedded"] = embedded
    return summary


def _reload_server():
    url = os.environ.get("RAG_SERVER_URL", "http://rag:8090").rstrip("/") + "/reload"
    try:
        with urllib.request.urlopen(urllib.request.Request(url, data=b"{}", method="POST"), timeout=120) as r:
            return r.status == 200
    except Exception:
        return False


def main(argv=None):
    ap = argparse.ArgumentParser(description="문서 소스를 조각내 임베딩한다")
    ap.add_argument("source")
    ap.add_argument("--from-dir", help="GitHub 대신 이 폴더에서 읽는다")
    ap.add_argument("--ref", help="sources.yml 의 ref 대신 쓸 브랜치·태그·커밋")
    ap.add_argument("--dry-run", action="store_true", help="조각 수만 세고 임베딩·저장은 하지 않는다")
    args = ap.parse_args(argv)

    settings = load_settings()
    source = settings.sources.get(args.source.lower())
    if source is None:
        raise SystemExit(f"sources.yml 에 없는 소스: {args.source} (있는 소스: {', '.join(settings.sources) or '없음'})")
    wanted = fetch.make_filter(source.include, source.exclude, source.extensions)
    if args.from_dir:
        commit, docs = "", fetch.read_dir(Path(args.from_dir), wanted)
    else:
        if not source.repo:
            raise SystemExit(f"{source.name}: repo 가 비어 있다 — sources.yml 에 repo 를 적거나 --from-dir 를 써 달라")
        commit, docs = fetch.fetch_github(source.repo, args.ref or source.ref,
                                          os.environ.get("RAG_GITHUB_TOKEN", ""), wanted, data_dir() / "tmp")
    print(f"[build] {source.name}: 문서 {len(docs)}개 (commit {commit[:8] or '-'})", flush=True)

    cache = EmbeddingCache(data_dir() / "emb-cache", settings.model)
    embedder = Embedder(settings.model)

    def progress(done, total):
        print(f"[build] 임베딩 {done}/{total}", flush=True)

    summary = build(source, settings.model, docs, commit, embedder, cache, dry_run=args.dry_run, progress=progress)
    print(json.dumps(summary, ensure_ascii=False), flush=True)
    if not args.dry_run:
        print("[build] 서버 다시 읽기: " + ("완료" if _reload_server() else "서버에 닿지 않음 — 서버를 띄우면 새 데이터를 읽는다"),
              flush=True)


if __name__ == "__main__":
    main(sys.argv[1:])
