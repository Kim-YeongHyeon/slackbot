import numpy as np
import pytest

from app import build as build_mod
from app import corpus
from app.config import Source
from app.embedder import EmbeddingCache, embed_with_cache
from tests.fakes import FakeEmbedder

DOC_A = "# Alpha\n\n## One\n\n" + "alpha one content. " * 6 + "\n\n## Two\n\n" + "alpha two content. " * 6 + "\n"
DOC_B = "# Beta\n\n## One\n\n" + "beta content here. " * 6 + "\n"


@pytest.fixture
def source(tmp_path, monkeypatch):
    monkeypatch.setenv("RAG_DATA_DIR", str(tmp_path))
    return Source(name="demo", repo="org/repo")


def run(source, docs, tmp_path, embedder=None, **kw):
    embedder = embedder or FakeEmbedder()
    cache = EmbeddingCache(tmp_path / "emb-cache", "fake-model")
    return build_mod.build(source, "fake-model", docs, "abc123", embedder, cache, **kw), embedder


def test_first_build_writes_numbered_chunks_vectors_and_manifest(source, tmp_path):
    summary, _ = run(source, {"docs/a.md": DOC_A, "docs/b.md": DOC_B}, tmp_path)
    chunks, vectors, manifest = corpus.load(source.dir)
    assert summary["chunks"] == 3 and summary["added"] == 3 and summary["embedded"] == 3
    assert [c["no"] for c in chunks] == [1, 2, 3]
    assert vectors.shape[0] == 3 and manifest["dim"] == vectors.shape[1]
    assert manifest["commit"] == "abc123" and manifest["model"] == "fake-model" and manifest["next_no"] == 4


def test_rebuild_embeds_only_changed_chunks_and_keeps_numbers(source, tmp_path):
    run(source, {"docs/a.md": DOC_A, "docs/b.md": DOC_B}, tmp_path)
    changed = DOC_A.replace("alpha two content.", "alpha two rewritten.")
    summary, embedder = run(source, {"docs/a.md": changed, "docs/b.md": DOC_B}, tmp_path)
    chunks, _, manifest = corpus.load(source.dir)
    assert summary["embedded"] == 1 and summary["added"] == 1 and summary["removed"] == 1
    assert sum(len(call) for call in embedder.doc_calls) == 1
    assert sorted(c["no"] for c in chunks) == [1, 3, 4]
    assert manifest["removed_nos"] == [2] and manifest["next_no"] == 5


def test_removed_document_returns_from_cache_without_embedding(source, tmp_path):
    run(source, {"docs/a.md": DOC_A, "docs/b.md": DOC_B}, tmp_path)
    run(source, {"docs/a.md": DOC_A}, tmp_path)
    summary, embedder = run(source, {"docs/a.md": DOC_A, "docs/b.md": DOC_B}, tmp_path)
    assert summary["embedded"] == 0 and embedder.doc_calls == []
    assert summary["added"] == 1          # 번호는 새로 받는다 (지워진 번호를 다시 쓰지 않음)


def test_dry_run_counts_without_embedding_or_writing(source, tmp_path):
    summary, embedder = run(source, {"docs/a.md": DOC_A}, tmp_path, dry_run=True)
    assert summary["chunks"] == 2 and summary["to_embed"] == 2 and "embedded" not in summary
    assert embedder.doc_calls == [] and not source.dir.exists()


def test_no_matching_documents_aborts_and_keeps_existing_data(source, tmp_path):
    run(source, {"docs/a.md": DOC_A}, tmp_path)
    with pytest.raises(SystemExit):
        run(source, {}, tmp_path)
    assert len(corpus.load_chunks(source.dir)) == 2


def test_secret_like_values_are_masked_before_saving(source, tmp_path):
    doc = "# Setup\n\n## Login\n\nRun the login with DB_PASSWORD=Sup3rS3cretValue9 and continue the setup.\n"
    summary, _ = run(source, {"docs/s.md": doc}, tmp_path)
    assert summary["redacted"] == 1
    assert "Sup3rS3cretValue9" not in corpus.load_chunks(source.dir)[0]["text"]


def test_cache_survives_reopen_and_duplicate_text_is_embedded_once(tmp_path):
    chunks = [{"hash": "h1", "text": "same"}, {"hash": "h1", "text": "same"}, {"hash": "h2", "text": "other"}]
    embedder = FakeEmbedder()
    vectors, embedded = embed_with_cache(chunks, embedder, EmbeddingCache(tmp_path, "org/model"))
    assert embedded == 2 and vectors.shape[0] == 3 and np.array_equal(vectors[0], vectors[1])
    again = FakeEmbedder()
    _, embedded2 = embed_with_cache(chunks, again, EmbeddingCache(tmp_path, "org/model"))
    assert embedded2 == 0 and again.doc_calls == []
    assert (tmp_path / "org__model.npz").exists()


def test_cache_is_separate_per_model(tmp_path):
    chunks = [{"hash": "h1", "text": "same"}]
    embed_with_cache(chunks, FakeEmbedder(), EmbeddingCache(tmp_path, "model-a"))
    _, embedded = embed_with_cache(chunks, FakeEmbedder(), EmbeddingCache(tmp_path, "model-b"))
    assert embedded == 1
