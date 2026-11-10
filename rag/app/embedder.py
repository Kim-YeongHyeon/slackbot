"""임베딩 모델과 캐시. 같은 내용은 다시 임베딩하지 않는다 (이 서버에서 bge-m3 는 초당 0.5조각)."""
import os
import threading
from pathlib import Path

import numpy as np

# 질문·문서에 접두어나 프롬프트 이름이 필요한 모델만 적는다. 없으면 그대로 넣는다 (bge-m3).
MODEL_PROMPTS = {
    "intfloat/multilingual-e5-small": {"query_prefix": "query: ", "doc_prefix": "passage: "},
    "intfloat/multilingual-e5-base": {"query_prefix": "query: ", "doc_prefix": "passage: "},
    "Qwen/Qwen3-Embedding-0.6B": {"query_prompt_name": "query"},
}


class Embedder:
    def __init__(self, model_name: str, batch_size: int = 8):
        self.model_name = model_name
        self.batch_size = batch_size
        self._prompts = MODEL_PROMPTS.get(model_name, {})
        self._model = None
        self._lock = threading.Lock()

    def _load(self):
        if self._model is None:
            import torch
            from sentence_transformers import SentenceTransformer

            torch.set_num_threads(int(os.environ.get("RAG_TORCH_THREADS", "2")))
            self._model = SentenceTransformer(self.model_name, device="cpu")
            self._model.max_seq_length = 512
        return self._model

    def warm(self):
        self.encode_query("warm up")

    def encode_docs(self, texts) -> np.ndarray:
        prefix = self._prompts.get("doc_prefix", "")
        with self._lock:
            vecs = self._load().encode([prefix + t for t in texts], batch_size=self.batch_size,
                                       normalize_embeddings=True, show_progress_bar=False)
        return np.asarray(vecs, dtype=np.float32)

    def encode_query(self, text: str) -> np.ndarray:
        kwargs = {}
        if "query_prompt_name" in self._prompts:
            kwargs["prompt_name"] = self._prompts["query_prompt_name"]
        with self._lock:
            vec = self._load().encode([self._prompts.get("query_prefix", "") + text],
                                      normalize_embeddings=True, show_progress_bar=False, **kwargs)[0]
        return np.asarray(vec, dtype=np.float32)


class EmbeddingCache:
    """내용 해시 → 벡터. 모델마다 파일 하나. 쓰지 않게 된 조각도 남겨 둬서 소스를 넣었다 뺐다 해도 다시 임베딩하지 않는다."""

    def __init__(self, dir_: Path, model_name: str):
        self.path = dir_ / (model_name.replace("/", "__") + ".npz")
        self._map = {}
        if self.path.exists():
            with np.load(self.path) as data:
                for h, v in zip(data["hashes"], data["vectors"]):
                    self._map[str(h)] = v

    def __len__(self):
        return len(self._map)

    def get(self, h: str):
        return self._map.get(h)

    def put(self, h: str, vec):
        self._map[h] = np.asarray(vec, dtype=np.float32)

    def save(self):
        if not self._map:
            return
        self.path.parent.mkdir(parents=True, exist_ok=True)
        tmp = self.path.with_name(self.path.name + ".tmp.npz")
        hashes = sorted(self._map)
        np.savez(tmp, hashes=np.array(hashes), vectors=np.stack([self._map[h] for h in hashes]))
        os.replace(tmp, self.path)


def embed_with_cache(chunks, embedder, cache: EmbeddingCache, save_every: int = 200, progress=None):
    """조각 순서대로 벡터 행렬을 돌려준다. 캐시에 없는 조각만 임베딩하고, 중간에 끊겨도 한 만큼은 남게 주기적으로 저장한다."""
    missing = []
    seen = set()
    for c in chunks:
        if cache.get(c["hash"]) is None and c["hash"] not in seen:
            seen.add(c["hash"])
            missing.append(c)
    for start in range(0, len(missing), save_every):
        batch = missing[start:start + save_every]
        vecs = embedder.encode_docs([c["text"] for c in batch])
        for c, v in zip(batch, vecs):
            cache.put(c["hash"], v)
        cache.save()
        if progress:
            progress(min(start + save_every, len(missing)), len(missing))
    if not chunks:
        return np.zeros((0, 0), dtype=np.float32), 0
    return np.stack([cache.get(c["hash"]) for c in chunks]), len(missing)
