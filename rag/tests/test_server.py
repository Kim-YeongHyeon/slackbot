import json
import threading
import urllib.error
import urllib.request
from http.server import ThreadingHTTPServer

import pytest

from app import build as build_mod
from app.config import Settings, Source
from app.embedder import EmbeddingCache
from app.server import ApiError, Registry, make_handler
from tests.fakes import FakeEmbedder

DOCS = {
    "docs/rollback.md": "# Rollback\n\n## Steps\n\nRoll back the release with helm rollback and check every pod.\n",
    "docs/node.md": "# Node requirements\n\n## Kernel\n\nSet fs.aio-max-nr to 1048576 on every node before install.\n",
    "docs/audit.md": "# Audit\n\n## Setup\n\nEnable the audit log in production with the audit flag enabled.\n",
}


@pytest.fixture
def registry(tmp_path, monkeypatch):
    monkeypatch.setenv("RAG_DATA_DIR", str(tmp_path))
    demo = Source(name="demo", repo="org/repo", mode="vector")
    empty = Source(name="empty", repo="org/none")
    build_mod.build(demo, "fake-model", DOCS, "abc123", FakeEmbedder(),
                    EmbeddingCache(tmp_path / "emb-cache", "fake-model"))
    reg = Registry(lambda: Settings(model="fake-model", sources={"demo": demo, "empty": empty}), FakeEmbedder)
    reg.reload()
    return reg


def test_search_returns_numbered_hits_with_source_info(registry):
    out = registry.search({"source": "demo", "query": "helm rollback release", "top_k": 2})
    assert out["repo"] == "org/repo" and out["commit"] == "abc123" and out["mode"] == "vector"
    assert len(out["hits"]) == 2
    top = out["hits"][0]
    assert top["path"] == "docs/rollback.md" and top["heading"] == "Rollback > Steps"
    assert top["no"] >= 1 and "helm rollback" in top["text"]


def test_source_name_is_case_insensitive_and_hybrid_can_be_requested(registry):
    out = registry.search({"source": "DEMO", "query": "설정", "keywords": ["fs.aio-max-nr"], "mode": "hybrid",
                           "vs_weight": 0.2, "top_k": 1})
    assert out["mode"] == "hybrid" and out["hits"][0]["path"] == "docs/node.md"


def test_unknown_source_lists_available_sources(registry):
    with pytest.raises(ApiError) as e:
        registry.search({"source": "nope", "query": "x"})
    assert e.value.status == 404 and e.value.body == {"error": "unknown_source", "sources": ["demo"]}


def test_source_without_data_is_not_ready(registry):
    with pytest.raises(ApiError) as e:
        registry.search({"source": "empty", "query": "x"})
    assert e.value.status == 503 and e.value.body["detail"] == "not_built"


@pytest.mark.parametrize("req, code", [
    ({"source": "demo", "query": "  "}, "empty_query"),
    ({"source": "demo", "query": "x" * 2001}, "query_too_long"),
    ({"source": "demo", "query": "x", "mode": "fulltext"}, "bad_mode"),
    ({"source": "demo", "query": "x", "top_k": "many"}, "bad_number"),
    ({"source": "demo", "query": "x", "keywords": "not-a-list"}, "bad_keywords"),
])
def test_bad_requests_are_rejected(registry, req, code):
    with pytest.raises(ApiError) as e:
        registry.search(req)
    assert e.value.status == 400 and e.value.body["error"] == code


def test_top_k_is_clamped(registry):
    assert len(registry.search({"source": "demo", "query": "x", "top_k": 999})["hits"]) == 3
    assert len(registry.search({"source": "demo", "query": "x", "top_k": 0})["hits"]) == 3   # 0 → 기본값 6


def test_data_built_with_another_model_is_not_served(tmp_path, monkeypatch):
    monkeypatch.setenv("RAG_DATA_DIR", str(tmp_path))
    demo = Source(name="demo", repo="org/repo")
    build_mod.build(demo, "old-model", DOCS, "abc", FakeEmbedder(), EmbeddingCache(tmp_path / "c", "old-model"))
    reg = Registry(lambda: Settings(model="new-model", sources={"demo": demo}), FakeEmbedder)
    health = reg.reload()
    assert health["sources"] == {} and health["problems"]["demo"].startswith("model_mismatch")


def test_reload_picks_up_a_rebuild(registry, tmp_path):
    before = registry.health()["sources"]["demo"]["chunks"]
    more = dict(DOCS, **{"docs/numa.md": "# NUMA\n\n## Placement\n\nUse --numa-auto so compute stays on one node.\n"})
    build_mod.build(Source(name="demo", repo="org/repo"), "fake-model", more, "def456", FakeEmbedder(),
                    EmbeddingCache(tmp_path / "emb-cache", "fake-model"))
    after = registry.reload()["sources"]["demo"]
    assert after["chunks"] == before + 1 and after["commit"] == "def456"


@pytest.fixture
def http(registry):
    server = ThreadingHTTPServer(("127.0.0.1", 0), make_handler(registry))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    yield f"http://127.0.0.1:{server.server_address[1]}"
    server.shutdown()


def call(base, path, body=None, raw=None):
    data = raw if raw is not None else (json.dumps(body).encode() if body is not None else None)
    req = urllib.request.Request(base + path, data=data, method="POST" if data is not None else "GET")
    try:
        with urllib.request.urlopen(req, timeout=5) as r:
            return r.status, json.load(r)
    except urllib.error.HTTPError as e:
        return e.code, json.load(e)


def test_http_search_health_and_reload(http):
    status, body = call(http, "/search", {"source": "demo", "query": "audit log 켜는 법", "top_k": 1})
    assert status == 200 and body["hits"][0]["path"] == "docs/audit.md"
    status, body = call(http, "/health")
    assert status == 200 and body["sources"]["demo"]["chunks"] == 3 and body["problems"] == {"empty": "not_built"}
    assert call(http, "/reload", {})[0] == 200


def test_http_errors(http):
    assert call(http, "/search", {"source": "nope", "query": "x"}) == (
        404, {"error": "unknown_source", "sources": ["demo"]})
    assert call(http, "/search", raw=b"{not json")[1] == {"error": "bad_json"}
    assert call(http, "/search", raw=b"[1,2]")[1] == {"error": "bad_json"}
    assert call(http, "/nope")[0] == 404
    assert call(http, "/nope", {})[0] == 404
