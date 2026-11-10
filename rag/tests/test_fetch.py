import io
import tarfile

from app import fetch


def test_glob_double_star_crosses_directories_and_single_star_does_not():
    assert fetch.glob_to_regex("docs/**").match("docs/a/b/c.md")
    assert fetch.glob_to_regex("docs/**/*.md").match("docs/c.md")
    assert fetch.glob_to_regex("docs/**/*.md").match("docs/a/b/c.md")
    assert fetch.glob_to_regex("docs/*.md").match("docs/c.md")
    assert not fetch.glob_to_regex("docs/*.md").match("docs/a/c.md")
    assert not fetch.glob_to_regex("docs/**").match("src/docs/a.md")


def test_glob_special_characters_are_literal():
    assert fetch.glob_to_regex("docs/a+b.md").match("docs/a+b.md")
    assert not fetch.glob_to_regex("docs/a.md").match("docs/abmd")


def test_filter_applies_include_exclude_and_extension():
    wanted = fetch.make_filter(["docs/runbooks/**", "docs/ha/**"], ["docs/runbooks/draft/**"], [".md"])
    assert wanted("docs/runbooks/kms/rollback.md")
    assert wanted("docs/ha/x.MD")
    assert not wanted("docs/runbooks/draft/wip.md")
    assert not wanted("docs/design/x.md")
    assert not wanted("docs/runbooks/diagram.png")


def test_filter_without_include_takes_everything_with_the_extension():
    wanted = fetch.make_filter([], [], [".md", ".rst"])
    assert wanted("README.md") and wanted("a/b.rst") and not wanted("a/b.py")


def _tarball(tmp_path, members):
    path = tmp_path / "repo.tgz"
    with tarfile.open(path, "w:gz") as tar:
        for name, data, kind in members:
            info = tarfile.TarInfo(name)
            if kind == "file":
                info.size = len(data)
                tar.addfile(info, io.BytesIO(data))
            else:
                info.type = tarfile.SYMTYPE
                info.linkname = data.decode()
                tar.addfile(info)
    return path


def test_read_tarball_strips_top_dir_and_skips_links_and_unwanted(tmp_path):
    path = _tarball(tmp_path, [
        ("org-repo-abc123/docs/a.md", "# A\n한글".encode(), "file"),
        ("org-repo-abc123/docs/b.txt", b"no", "file"),
        ("org-repo-abc123/docs/link.md", b"/etc/passwd", "link"),
        ("org-repo-abc123/src/c.md", b"# C", "file"),
    ])
    docs = fetch.read_tarball(path, fetch.make_filter(["docs/**"], [], [".md"]))
    assert docs == {"docs/a.md": "# A\n한글"}


def test_read_tarball_skips_oversized_files(tmp_path, monkeypatch):
    monkeypatch.setattr(fetch, "MAX_FILE_BYTES", 10)
    path = _tarball(tmp_path, [("top/docs/big.md", b"x" * 11, "file"), ("top/docs/ok.md", b"x" * 10, "file")])
    assert list(fetch.read_tarball(path, fetch.make_filter([], [], [".md"]))) == ["docs/ok.md"]


def test_read_dir_reads_matching_files_and_ignores_symlinks(tmp_path):
    (tmp_path / "docs").mkdir()
    (tmp_path / "docs" / "a.md").write_text("# A", encoding="utf-8")
    (tmp_path / "secret.md").write_text("outside include", encoding="utf-8")
    (tmp_path / "docs" / "link.md").symlink_to(tmp_path / "secret.md")
    docs = fetch.read_dir(tmp_path, fetch.make_filter(["docs/**"], [], [".md"]))
    assert docs == {"docs/a.md": "# A"}
