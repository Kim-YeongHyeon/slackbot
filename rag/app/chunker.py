"""마크다운 문서를 제목 단위 조각으로 나눈다. 코드 블록 안의 # 는 제목으로 보지 않는다."""
import re

MAX_CHARS = 1200
MIN_CHARS = 40
_HEADING = re.compile(r"^(#{1,4})\s+(.*)")
_FENCE = re.compile(r"^\s*(```|~~~)")
_TITLE = re.compile(r"(?m)^#\s+(.*)")


def split_sections(text: str):
    """[(제목 경로, 본문)] — 제목 경로는 'A > B > C'."""
    out, path, buf = [], [], []
    fence = None

    def flush():
        body = "\n".join(buf).strip()
        if body:
            out.append((" > ".join(p for p in path if p), body))
        buf.clear()

    for line in text.splitlines():
        m = _FENCE.match(line)
        if m:
            if fence is None:
                fence = m.group(1)
            elif fence == m.group(1):
                fence = None
        h = None if fence else _HEADING.match(line)
        if h:
            flush()
            level = len(h.group(1))
            del path[level - 1:]
            while len(path) < level - 1:
                path.append("")
            path.append(h.group(2).strip())
        else:
            buf.append(line)
    flush()
    return out


def pack(body: str, max_chars: int = MAX_CHARS):
    """빈 줄로 나눈 문단을 max_chars 까지 이어 붙인다. 코드 블록은 한 문단으로 다룬다."""
    out, cur = [], ""
    for part in _paragraphs(body):
        if len(cur) + len(part) + 2 <= max_chars:
            cur = (cur + "\n\n" + part).strip()
            continue
        if cur:
            out.append(cur)
        while len(part) > max_chars * 1.5:
            cut = part.rfind("\n", 0, max_chars)
            cut = cut if cut > 200 else max_chars
            out.append(part[:cut])
            part = part[cut:]
        cur = part
    if cur:
        out.append(cur)
    return out


def _paragraphs(body: str):
    """빈 줄 기준으로 나누되, 코드 블록 안의 빈 줄에서는 나누지 않는다."""
    parts, buf, fence = [], [], None
    for line in body.splitlines():
        m = _FENCE.match(line)
        if m:
            if fence is None:
                fence = m.group(1)
            elif fence == m.group(1):
                fence = None
        if not line.strip() and fence is None:
            if buf:
                parts.append("\n".join(buf))
                buf = []
        else:
            buf.append(line)
    if buf:
        parts.append("\n".join(buf))
    return parts


def chunk_document(path: str, text: str):
    """[{path, heading, text}] — text 앞에 '문서 제목 | 제목 경로' 를 붙여 조각만 봐도 맥락을 알 수 있게 한다."""
    m = _TITLE.search(text)
    title = m.group(1).strip() if m else path
    chunks = []
    for heading, body in split_sections(text):
        for piece in pack(body):
            if len(piece) < MIN_CHARS:
                continue
            chunks.append({"path": path, "heading": heading, "text": f"{title} | {heading}\n{piece}"})
    return chunks
