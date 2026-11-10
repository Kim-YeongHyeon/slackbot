import pytest

from app.config import DEFAULT_MODEL, parse_settings
from app.evalcli import first_gold_rank, load_questions, metrics


def test_parse_settings_defaults_and_values():
    s = parse_settings({"sources": {"EnVector": {"repo": "org/repo", "include": ["docs/**"], "mode": "hybrid"}}})
    src = s.sources["envector"]
    assert s.model == DEFAULT_MODEL
    assert (src.repo, src.ref, src.include, src.extensions, src.backend, src.mode) == (
        "org/repo", "main", ("docs/**",), (".md",), "local", "hybrid")


def test_parse_settings_empty_file():
    assert parse_settings(None).sources == {} and parse_settings({}).model == DEFAULT_MODEL


@pytest.mark.parametrize("raw", [
    {"sources": {"bad name": {}}},
    {"sources": {"../x": {}}},
    {"sources": {"a": {"backend": "pinecone"}}},
    {"sources": {"a": {"mode": "fulltext"}}},
])
def test_parse_settings_rejects_bad_values(raw):
    with pytest.raises(ValueError):
        parse_settings(raw)


def test_metrics():
    assert metrics([1, 3, None, 12]) == {"n": 4, "r1": 0.25, "r5": 0.5, "r10": 0.5, "mrr": round((1 + 1/3 + 1/12) / 4, 3)}
    assert metrics([])["n"] == 0


def test_first_gold_rank():
    assert first_gold_rank(["a", "b", "c"], {"c", "z"}) == 3
    assert first_gold_rank(["a"], {"z"}) is None


def test_load_questions_accepts_both_shapes(tmp_path):
    p = tmp_path / "q.json"
    p.write_text('[{"q": "질문1", "gold": ["a.md"], "keywords": ["rollback"]}, ["질문2", ["b.md", "c.md"]]]',
                 encoding="utf-8")
    qs = load_questions(p)
    assert qs[0] == {"q": "질문1", "gold": {"a.md"}, "keywords": ["rollback"]}
    assert qs[1] == {"q": "질문2", "gold": {"b.md", "c.md"}, "keywords": []}
