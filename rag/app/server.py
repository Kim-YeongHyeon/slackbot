"""검색 서버. 모델과 조각 원문을 메모리에 올려 두고 봇(Spring)의 요청에 답한다.

  POST /search  {"source": "envector", "query": "...", "keywords": ["..."], "top_k": 6, "mode": "vector"}
  GET  /health
  POST /reload  데이터 폴더를 다시 읽는다 (build 뒤에 부른다)
"""
import json
import os
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

from . import corpus
from .config import load_settings
from .embedder import Embedder
from .store import MODES, LocalStore
from .tokenizer import tokenize

MAX_QUERY_CHARS = 2000
MAX_TOP_K = 20
MAX_BODY_BYTES = 64 * 1024


class ApiError(Exception):
    def __init__(self, status: int, code: str, **extra):
        super().__init__(code)
        self.status, self.body = status, {"error": code, **extra}


class Registry:
    """소스 이름 → 메모리에 올린 조각과 검색 백엔드."""

    def __init__(self, settings_loader=load_settings, embedder_factory=Embedder):
        self._settings_loader = settings_loader
        self._embedder_factory = embedder_factory
        self._lock = threading.Lock()
        self.model = None
        self.embedder = None
        self.loaded = {}
        self.problems = {}

    def reload(self):
        settings = self._settings_loader()
        loaded, problems = {}, {}
        for name, source in settings.sources.items():
            try:
                chunks, vectors, manifest = corpus.load(source.dir)
                if not chunks:
                    problems[name] = "not_built"
                elif manifest.get("model") != settings.model:
                    problems[name] = f"model_mismatch: 데이터는 {manifest.get('model')}, 설정은 {settings.model}"
                else:
                    loaded[name] = {"source": source, "chunks": chunks, "manifest": manifest,
                                    "store": LocalStore(chunks, vectors)}
            except Exception as e:  # 소스 하나가 깨져도 나머지는 계속 쓴다
                problems[name] = f"load_failed: {e}"
        with self._lock:
            if self.embedder is None or self.model != settings.model:
                self.embedder = self._embedder_factory(settings.model)
                self.model = settings.model
            self.loaded, self.problems = loaded, problems
        return self.health()

    def health(self):
        return {
            "status": "ok", "model": self.model,
            "sources": {n: {"chunks": len(s["chunks"]), "commit": s["manifest"].get("commit", ""),
                            "backend": s["store"].backend, "mode": s["source"].mode,
                            "built_at": s["manifest"].get("built_at", "")} for n, s in self.loaded.items()},
            "problems": self.problems,
        }

    def search(self, req: dict):
        name = str(req.get("source", "")).strip().lower()
        query = str(req.get("query", "")).strip()
        if not query:
            raise ApiError(400, "empty_query")
        if len(query) > MAX_QUERY_CHARS:
            raise ApiError(400, "query_too_long", max=MAX_QUERY_CHARS)
        entry = self.loaded.get(name)
        if entry is None:
            if name in self.problems:
                raise ApiError(503, "source_not_ready", source=name, detail=self.problems[name])
            raise ApiError(404, "unknown_source", sources=sorted(self.loaded))
        mode = req.get("mode") or entry["source"].mode
        if mode not in MODES:
            raise ApiError(400, "bad_mode", modes=list(MODES))
        try:
            top_k = max(1, min(MAX_TOP_K, int(req.get("top_k") or 6)))
            vs_weight = float(req.get("vs_weight", 0.5))
        except (TypeError, ValueError):
            raise ApiError(400, "bad_number")
        keywords = req.get("keywords") or []
        if not isinstance(keywords, list):
            raise ApiError(400, "bad_keywords")
        terms = tokenize(" ".join([query] + [str(k) for k in keywords])) if mode == "hybrid" else None
        hits = entry["store"].search(self.embedder.encode_query(query), terms=terms, top_k=top_k,
                                     mode=mode, vs_weight=vs_weight)
        chunks, manifest = entry["chunks"], entry["manifest"]
        return {
            "source": name, "repo": manifest.get("repo", ""), "commit": manifest.get("commit", ""), "mode": mode,
            "hits": [{"no": chunks[i]["no"], "path": chunks[i]["path"], "heading": chunks[i]["heading"],
                      "text": chunks[i]["text"], "score": round(score, 5)} for i, score in hits],
        }


def make_handler(registry: Registry):
    class Handler(BaseHTTPRequestHandler):
        protocol_version = "HTTP/1.1"

        def log_message(self, fmt, *args):  # 질문 내용을 접근 로그에 남기지 않는다
            pass

        def _send(self, status: int, body: dict):
            data = json.dumps(body, ensure_ascii=False).encode("utf-8")
            self.send_response(status)
            self.send_header("Content-Type", "application/json; charset=utf-8")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def _json_body(self):
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY_BYTES:
                raise ApiError(413, "body_too_large")
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
            except ValueError:
                raise ApiError(400, "bad_json")
            if not isinstance(body, dict):
                raise ApiError(400, "bad_json")
            return body

        def do_GET(self):
            if self.path == "/health":
                self._send(200, registry.health())
            else:
                self._send(404, {"error": "not_found"})

        def do_POST(self):
            try:
                if self.path == "/search":
                    self._send(200, registry.search(self._json_body()))
                elif self.path == "/reload":
                    self._json_body()
                    self._send(200, registry.reload())
                else:
                    self._send(404, {"error": "not_found"})
            except ApiError as e:
                self._send(e.status, e.body)
            except Exception as e:
                print(f"[server] {self.path} 실패: {type(e).__name__}: {e}", flush=True)
                self._send(500, {"error": "internal"})

    return Handler


def main():
    registry = Registry()
    state = registry.reload()
    print(f"[server] 소스: {state['sources']} 문제: {state['problems']}", flush=True)
    registry.embedder.warm()
    port = int(os.environ.get("RAG_PORT", "8090"))
    print(f"[server] 모델 {registry.model} 준비 완료, :{port}", flush=True)
    ThreadingHTTPServer(("0.0.0.0", port), make_handler(registry)).serve_forever()


if __name__ == "__main__":
    main()
