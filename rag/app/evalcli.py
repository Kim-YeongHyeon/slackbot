"""질문 파일로 검색 품질을 잰다. 소스·검색 방식·가중치를 바꿀 때마다 비교하는 용도.

  python -m app.evalcli <소스> [--questions FILE] [--modes vector,hybrid] [--vs-weights 0.5,0.7] [--show-misses]

질문 파일(JSON): [{"q": "질문", "gold": ["정답 문서 경로", ...], "keywords": ["선택: 영어 키워드"]}, ...]
"""
import argparse
import json
import sys

from . import corpus
from .config import data_dir, load_settings
from .embedder import Embedder
from .store import LocalStore
from .tokenizer import tokenize

DEPTH = 50


def load_questions(path):
    with open(path, encoding="utf-8") as f:
        raw = json.load(f)
    out = []
    for item in raw:
        if isinstance(item, dict):
            out.append({"q": item["q"], "gold": set(item["gold"]), "keywords": item.get("keywords") or []})
        else:  # ["질문", ["경로", ...]]
            out.append({"q": item[0], "gold": set(item[1]), "keywords": []})
    return out


def first_gold_rank(paths, gold):
    for rank, path in enumerate(paths, start=1):
        if path in gold:
            return rank
    return None


def metrics(ranks):
    n = len(ranks) or 1
    return {
        "n": len(ranks),
        "r1": round(sum(r == 1 for r in ranks) / n, 3),
        "r5": round(sum(bool(r and r <= 5) for r in ranks) / n, 3),
        "r10": round(sum(bool(r and r <= 10) for r in ranks) / n, 3),
        "mrr": round(sum(1 / r for r in ranks if r) / n, 3),
    }


def evaluate(store, chunks, questions, query_vecs, mode, vs_weight=0.5, use_keywords=False):
    ranks = []
    for q, vec in zip(questions, query_vecs):
        text = " ".join([q["q"]] + (q["keywords"] if use_keywords else []))
        hits = store.search(vec, terms=tokenize(text), top_k=DEPTH, mode=mode, vs_weight=vs_weight)
        ranks.append(first_gold_rank([chunks[i]["path"] for i, _ in hits], q["gold"]))
    return ranks


def main(argv=None):
    ap = argparse.ArgumentParser(description="검색 품질 측정")
    ap.add_argument("source")
    ap.add_argument("--questions")
    ap.add_argument("--modes", default="vector,hybrid")
    ap.add_argument("--vs-weights", default="0.5,0.7")
    ap.add_argument("--show-misses", action="store_true", help="5위 안에 못 든 질문을 보여 준다")
    args = ap.parse_args(argv)

    settings = load_settings()
    source = settings.sources.get(args.source.lower())
    if source is None:
        raise SystemExit(f"sources.yml 에 없는 소스: {args.source}")
    chunks, vectors, manifest = corpus.load(source.dir)
    if not chunks:
        raise SystemExit(f"{source.name}: 아직 만든 데이터가 없다 — python -m app.build {source.name}")
    questions = load_questions(args.questions or data_dir() / "eval" / f"{source.name}.json")
    paths = {c["path"] for c in chunks}
    for q in questions:
        missing = q["gold"] - paths
        if missing:
            print(f"[eval] 정답 문서가 데이터에 없음: {sorted(missing)} ← {q['q']}")
    embedder = Embedder(manifest["model"])
    query_vecs = [embedder.encode_query(q["q"]) for q in questions]
    store = LocalStore(chunks, vectors)
    has_keywords = any(q["keywords"] for q in questions)

    runs = []
    for mode in args.modes.split(","):
        if mode == "vector":
            runs.append(("vector", "vector", 0.5, False))
            continue
        for w in [float(x) for x in args.vs_weights.split(",")]:
            runs.append((f"hybrid w={w}", "hybrid", w, False))
            if has_keywords:
                runs.append((f"hybrid+keywords w={w}", "hybrid", w, True))
    print(f"소스 {source.name}: 조각 {len(chunks)}개, 질문 {len(questions)}개, 모델 {manifest['model']}")
    for label, mode, w, kw in runs:
        ranks = evaluate(store, chunks, questions, query_vecs, mode, w, kw)
        print(f"{label:28s} {json.dumps(metrics(ranks))}")
        if args.show_misses:
            for q, r in zip(questions, ranks):
                if not r or r > 5:
                    print(f"    {r or '-':>3}  {q['q']}")


if __name__ == "__main__":
    main(sys.argv[1:])
