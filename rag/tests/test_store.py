import numpy as np
import pytest

from app.store import Bm25, LocalStore, rrf
from app.tokenizer import tokenize
from tests.fakes import fake_vector

TEXTS = [
    "Rollback | Steps\nRoll back the release with helm rollback and check the pods",
    "Node requirements | Kernel\nSet fs.aio-max-nr to 1048576 on every node",
    "Audit | Setup\nEnable the audit log in production with the audit flag",
    "NUMA | Placement\nUse --numa-auto so compute is pinned to one NUMA node",
]


def make_store():
    chunks = [{"no": i + 1, "path": f"d{i}.md", "heading": "h", "text": t} for i, t in enumerate(TEXTS)]
    return LocalStore(chunks, np.stack([fake_vector(t) for t in TEXTS]))


def test_rrf_orders_by_weighted_reciprocal_rank():
    fused = rrf([["a", "b", "c"], ["c", "a"]], [0.5, 0.5], k=60)
    assert [i for i, _ in fused] == ["a", "c", "b"]
    assert fused[0][1] == pytest.approx(0.5 / 61 + 0.5 / 62)


def test_rrf_weight_zero_ignores_that_ranking():
    assert [i for i, _ in rrf([["a", "b"], ["b", "a"]], [1.0, 0.0])] == ["a", "b"]


def test_bm25_ranks_the_document_with_the_rare_term_first():
    bm = Bm25([tokenize(t) for t in TEXTS])
    scores = bm.scores(tokenize("fs.aio-max-nr"))
    assert int(np.argmax(scores)) == 1 and scores[0] == 0


def test_bm25_unknown_terms_score_zero():
    assert not Bm25([tokenize(t) for t in TEXTS]).scores(["nonexistentterm"]).any()


def test_vector_search_returns_top_k_in_score_order():
    hits = make_store().search(fake_vector("helm rollback release"), top_k=2)
    assert len(hits) == 2 and hits[0][0] == 0 and hits[0][1] >= hits[1][1]


def test_hybrid_lets_an_exact_identifier_win_over_a_vague_vector():
    store = make_store()
    vague = fake_vector("production audit log")          # 벡터는 문서 2를 가리킨다
    assert store.search(vague, top_k=1)[0][0] == 2
    hits = store.search(vague, terms=tokenize("--numa-auto 옵션"), top_k=2, mode="hybrid", vs_weight=0.2)
    assert hits[0][0] == 3


def test_hybrid_without_matching_terms_falls_back_to_vector_order():
    store = make_store()
    q = fake_vector("helm rollback release")
    vec = [i for i, _ in store.search(q, top_k=4)]
    hyb = [i for i, _ in store.search(q, terms=["없는말"], top_k=4, mode="hybrid")]
    assert hyb == vec


def test_unknown_mode_is_rejected():
    with pytest.raises(ValueError):
        make_store().search(fake_vector("x"), mode="fulltext")


def test_empty_store_returns_nothing():
    assert LocalStore([], np.zeros((0, 4), dtype=np.float32)).search(np.zeros(4), top_k=3) == []
