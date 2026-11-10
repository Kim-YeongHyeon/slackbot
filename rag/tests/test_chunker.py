from app.chunker import MAX_CHARS, chunk_document, pack, split_sections

DOC = """# Rollback Guide

Intro paragraph that is long enough to be kept as its own chunk in the output.

## Steps

Run the command below to roll back the release to the previous version safely.

```bash
# this is a comment, not a heading
helm rollback app 3

kubectl get pods
```

### Verify

Check that every pod is Running and that the health endpoint answers with 200.
"""


def test_headings_inside_code_fence_are_not_sections():
    headings = [h for h, _ in split_sections(DOC)]
    assert headings == ["Rollback Guide", "Rollback Guide > Steps", "Rollback Guide > Steps > Verify"]


def test_chunk_text_carries_title_and_heading_path():
    chunks = chunk_document("docs/rollback.md", DOC)
    verify = [c for c in chunks if c["heading"].endswith("Verify")][0]
    assert verify["path"] == "docs/rollback.md"
    assert verify["text"].startswith("Rollback Guide | Rollback Guide > Steps > Verify\n")


def test_code_block_with_blank_line_stays_in_one_chunk():
    steps = [c for c in chunk_document("d.md", DOC) if c["heading"].endswith("Steps")]
    assert len(steps) == 1
    assert "helm rollback app 3" in steps[0]["text"] and "kubectl get pods" in steps[0]["text"]


def test_long_section_is_split_under_the_limit():
    body = "\n\n".join(f"Paragraph {i} " + "word " * 60 for i in range(12))
    pieces = pack(body)
    assert len(pieces) > 1
    assert all(len(p) <= MAX_CHARS for p in pieces)
    assert "".join(pieces).count("Paragraph") == 12


def test_oversized_single_paragraph_is_cut():
    pieces = pack("\n".join("line %d " % i + "x" * 80 for i in range(60)))
    assert len(pieces) > 1 and all(len(p) <= MAX_CHARS * 1.5 for p in pieces)


def test_tiny_sections_and_empty_documents_produce_nothing():
    assert chunk_document("a.md", "# Title\n\nshort\n") == []
    assert chunk_document("a.md", "") == []


def test_document_without_h1_uses_path_as_title():
    chunks = chunk_document("docs/x.md", "## Only\n\n" + "content " * 10)
    assert chunks[0]["text"].startswith("docs/x.md | Only\n")
