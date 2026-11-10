import numpy as np
import pytest

from app import corpus


def raw(path, text, heading="H"):
    return {"path": path, "heading": heading, "text": text}


def test_first_build_numbers_from_one():
    chunks, next_no, added, removed = corpus.assign_numbers([raw("a.md", "one"), raw("a.md", "two")], [], 1)
    assert [c["no"] for c in chunks] == [1, 2]
    assert (next_no, added, removed) == (3, [1, 2], [])


def test_unchanged_chunks_keep_numbers_and_removed_numbers_are_not_reused():
    first, next_no, _, _ = corpus.assign_numbers(
        [raw("a.md", "one"), raw("a.md", "two"), raw("b.md", "three")], [], 1)
    second, next_no2, added, removed = corpus.assign_numbers(
        [raw("a.md", "one"), raw("a.md", "two edited"), raw("b.md", "three"), raw("c.md", "four")], first, next_no)
    by_text = {c["text"]: c["no"] for c in second}
    assert by_text["one"] == 1 and by_text["three"] == 3
    assert by_text["two edited"] == 4 and by_text["four"] == 5
    assert added == [4, 5] and removed == [2] and next_no2 == 6


def test_same_text_in_two_files_gets_two_numbers_and_each_is_stable():
    first, next_no, _, _ = corpus.assign_numbers([raw("a.md", "same"), raw("b.md", "same")], [], 1)
    second, _, added, removed = corpus.assign_numbers([raw("b.md", "same"), raw("a.md", "same")], first, next_no)
    assert {c["path"]: c["no"] for c in second} == {"a.md": 1, "b.md": 2}
    assert added == [] and removed == []


def test_repeated_text_inside_one_file():
    first, next_no, _, _ = corpus.assign_numbers([raw("a.md", "dup"), raw("a.md", "dup")], [], 1)
    second, _, added, removed = corpus.assign_numbers([raw("a.md", "dup")], first, next_no)
    assert [c["no"] for c in second] == [1] and removed == [2] and added == []


def test_next_no_never_goes_below_existing_numbers():
    prev = [{"no": 9, "path": "a.md", "heading": "H", "text": "x", "hash": corpus.text_hash("x")}]
    chunks, next_no, added, _ = corpus.assign_numbers([raw("a.md", "x"), raw("a.md", "y")], prev, 1)
    assert [c["no"] for c in chunks] == [9, 10] and next_no == 11 and added == [10]


def test_save_and_load_round_trip(tmp_path):
    chunks, _, _, _ = corpus.assign_numbers([raw("a.md", "하나"), raw("a.md", "둘")], [], 1)
    vectors = np.arange(8, dtype=np.float32).reshape(2, 4)
    corpus.save(tmp_path, chunks, vectors, {"model": "m", "next_no": 3})
    loaded_chunks, loaded_vectors, manifest = corpus.load(tmp_path)
    assert loaded_chunks == chunks and manifest["next_no"] == 3
    assert np.array_equal(loaded_vectors, vectors)
    assert not list(tmp_path.glob("*.tmp"))


def test_save_rejects_mismatched_vector_count(tmp_path):
    chunks, _, _, _ = corpus.assign_numbers([raw("a.md", "one")], [], 1)
    with pytest.raises(ValueError):
        corpus.save(tmp_path, chunks, np.zeros((2, 4), dtype=np.float32), {})


def test_load_of_empty_dir_is_empty(tmp_path):
    assert corpus.load(tmp_path) == ([], None, {})


def test_load_rejects_vectors_that_do_not_match_chunks(tmp_path):
    chunks, _, _, _ = corpus.assign_numbers([raw("a.md", "one"), raw("a.md", "two")], [], 1)
    corpus.save(tmp_path, chunks, np.zeros((2, 4), dtype=np.float32), {"model": "m"})
    np.save(tmp_path / corpus.VECTORS, np.zeros((1, 4), dtype=np.float32))
    with pytest.raises(ValueError):
        corpus.load(tmp_path)
