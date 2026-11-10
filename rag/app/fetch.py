"""GitHub 저장소에서 문서 파일만 읽어 온다. tarball 을 디스크에 풀지 않고 필요한 파일만 메모리로 읽는다."""
import json
import os
import re
import tarfile
import tempfile
import urllib.error
import urllib.request
from pathlib import Path

API = "https://api.github.com"
MAX_FILE_BYTES = 2 * 1024 * 1024


def glob_to_regex(pattern: str):
    """** 는 디렉터리를 넘어가고, * 와 ? 는 한 디렉터리 안에서만 맞는다."""
    out, i = "", 0
    while i < len(pattern):
        if pattern.startswith("**/", i):
            out += "(?:.*/)?"
            i += 3
        elif pattern.startswith("**", i):
            out += ".*"
            i += 2
        elif pattern[i] == "*":
            out += "[^/]*"
            i += 1
        elif pattern[i] == "?":
            out += "[^/]"
            i += 1
        else:
            out += re.escape(pattern[i])
            i += 1
    return re.compile(out + r"\Z")


def make_filter(include, exclude, extensions):
    inc = [glob_to_regex(p) for p in include]
    exc = [glob_to_regex(p) for p in exclude]
    exts = tuple(e.lower() for e in extensions)

    def wanted(path: str) -> bool:
        if not path.lower().endswith(exts):
            return False
        if inc and not any(r.match(path) for r in inc):
            return False
        return not any(r.match(path) for r in exc)

    return wanted


class _NoRedirect(urllib.request.HTTPRedirectHandler):
    def redirect_request(self, *args, **kwargs):
        return None


def _request(url: str, token: str):
    headers = {"Accept": "application/vnd.github+json", "X-GitHub-Api-Version": "2022-11-28",
               "User-Agent": "rag-sidecar"}
    if token:
        headers["Authorization"] = "Bearer " + token
    return urllib.request.Request(url, headers=headers)


def resolve_commit(repo: str, ref: str, token: str) -> str:
    with urllib.request.urlopen(_request(f"{API}/repos/{repo}/commits/{ref}", token), timeout=30) as r:
        return json.load(r)["sha"]


def download_tarball(repo: str, sha: str, token: str, dest: Path):
    """tarball API 는 서명된 codeload 주소로 302 를 준다. 그 주소에는 토큰을 보내지 않는다."""
    opener = urllib.request.build_opener(_NoRedirect)
    try:
        with opener.open(_request(f"{API}/repos/{repo}/tarball/{sha}", token), timeout=30) as r:
            location = r.headers.get("Location")
    except urllib.error.HTTPError as e:
        if e.code not in (301, 302, 303, 307, 308):
            raise
        location = e.headers.get("Location")
    if not location:
        raise RuntimeError("tarball 주소를 받지 못했다")
    req = urllib.request.Request(location, headers={"User-Agent": "rag-sidecar"})
    with urllib.request.urlopen(req, timeout=300) as r, open(dest, "wb") as f:
        while True:
            block = r.read(1 << 20)
            if not block:
                break
            f.write(block)


def read_tarball(path: Path, wanted):
    """{저장소 기준 경로: 내용}. 일반 파일만 읽고, 맨 위 디렉터리(org-repo-sha/)는 뗀다."""
    docs = {}
    with tarfile.open(path, mode="r:gz") as tar:
        for member in tar:
            if not member.isreg() or "/" not in member.name:
                continue
            rel = member.name.split("/", 1)[1]
            if member.size > MAX_FILE_BYTES or not wanted(rel):
                continue
            docs[rel] = tar.extractfile(member).read().decode("utf-8", errors="replace")
    return docs


def read_dir(root: Path, wanted):
    docs = {}
    for dirpath, _, files in os.walk(root):
        for name in files:
            full = Path(dirpath) / name
            rel = full.relative_to(root).as_posix()
            if wanted(rel) and full.is_file() and not full.is_symlink() and full.stat().st_size <= MAX_FILE_BYTES:
                docs[rel] = full.read_text(encoding="utf-8", errors="replace")
    return docs


def fetch_github(repo: str, ref: str, token: str, wanted, tmp_dir: Path):
    """(커밋 sha, {경로: 내용})"""
    sha = resolve_commit(repo, ref, token)
    tmp_dir.mkdir(parents=True, exist_ok=True)
    fd, name = tempfile.mkstemp(suffix=".tgz", dir=tmp_dir)
    os.close(fd)
    try:
        download_tarball(repo, sha, token, Path(name))
        return sha, read_tarball(Path(name), wanted)
    finally:
        os.unlink(name)
