"""검색 백엔드. local 은 메모리의 numpy 행렬로 찾는다 — envector 서버 없이 개발·검증하고, 나중에 envector 결과와 비교하는 기준값으로 쓴다."""
import math
from collections import Counter, defaultdict

import numpy as np

from .tokenizer import tokenize

MODES = ("vector", "hybrid")


def rrf(rankings, weights, k: int = 60):
    """Reciprocal Rank Fusion. rankings: 순위대로 나열한 id 목록들. 반환: [(id, 점수)] 점수 높은 순."""
    scores = defaultdict(float)
    for ranking, weight in zip(rankings, weights):
        for rank, item in enumerate(ranking):
            scores[item] += weight / (k + rank + 1)
    return sorted(scores.items(), key=lambda kv: (-kv[1], kv[0]))


class Bm25:
    def __init__(self, token_lists, k1: float = 1.5, b: float = 0.75):
        self.k1, self.b = k1, b
        self.n = len(token_lists)
        self.lengths = np.array([len(t) for t in token_lists], dtype=np.float32)
        self.avg = float(self.lengths.mean()) if self.n else 0.0
        postings = defaultdict(list)
        for i, tokens in enumerate(token_lists):
            for term, tf in Counter(tokens).items():
                postings[term].append((i, tf))
        self.postings = {t: (np.array([i for i, _ in p]), np.array([tf for _, tf in p], dtype=np.float32))
                         for t, p in postings.items()}

    def scores(self, terms) -> np.ndarray:
        out = np.zeros(self.n, dtype=np.float32)
        for term in set(terms):
            if term not in self.postings:
                continue
            idx, tf = self.postings[term]
            idf = math.log(1 + (self.n - len(idx) + 0.5) / (len(idx) + 0.5))
            norm = tf + self.k1 * (1 - self.b + self.b * self.lengths[idx] / self.avg)
            out[idx] += idf * tf * (self.k1 + 1) / norm
        return out


class LocalStore:
    backend = "local"

    def __init__(self, chunks, vectors: np.ndarray):
        self.chunks = chunks
        self.vectors = np.asarray(vectors, dtype=np.float32)
        self._bm25 = None

    def _bm25_index(self):
        if self._bm25 is None:
            self._bm25 = Bm25([tokenize(c["text"]) for c in self.chunks])
        return self._bm25

    def search(self, query_vec, terms=None, top_k: int = 6, mode: str = "vector",
               vs_weight: float = 0.5, rrf_k: int = 60, candidates: int = 100):
        """[(조각 위치, 점수)] — 조각 위치는 chunks 목록의 인덱스."""
        if mode not in MODES:
            raise ValueError(f"mode 는 {MODES} 중 하나여야 한다: {mode!r}")
        if not self.chunks:
            return []
        sims = self.vectors @ np.asarray(query_vec, dtype=np.float32)
        dense = np.argsort(-sims)
        if mode == "vector":
            return [(int(i), float(sims[i])) for i in dense[:top_k]]
        bm = self._bm25_index().scores(terms or [])
        sparse = [int(i) for i in np.argsort(-bm) if bm[i] > 0][:candidates]
        fused = rrf([[int(i) for i in dense[:candidates]], sparse], [vs_weight, 1 - vs_weight], rrf_k)
        return [(i, float(s)) for i, s in fused[:top_k]]
