import hashlib

import numpy as np

from app.tokenizer import tokenize

DIM = 64


def fake_vector(text: str) -> np.ndarray:
    """단어 해시를 더한 가짜 임베딩 — 같은 단어가 많을수록 가깝다. 테스트가 torch 없이 돌게 한다."""
    vec = np.zeros(DIM, dtype=np.float32)
    for tok in tokenize(text):
        vec[int(hashlib.md5(tok.encode()).hexdigest(), 16) % DIM] += 1.0
    norm = np.linalg.norm(vec)
    return vec / norm if norm else vec


class FakeEmbedder:
    def __init__(self, model_name="fake-model"):
        self.model_name = model_name
        self.doc_calls = []

    def warm(self):
        pass

    def encode_docs(self, texts):
        self.doc_calls.append(list(texts))
        return np.stack([fake_vector(t) for t in texts])

    def encode_query(self, text):
        return fake_vector(text)
