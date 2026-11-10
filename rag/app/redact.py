"""조각에 넣기 전에 비밀값처럼 보이는 문자열을 가린다. 문서 원문은 Slack 답변에 그대로 인용될 수 있다."""
import re

MASK = "[가림]"

_TOKENS = [
    re.compile(r"gh[pousr]_[A-Za-z0-9]{20,}"),
    re.compile(r"github_pat_[A-Za-z0-9_]{20,}"),
    re.compile(r"xox[abprs]-[A-Za-z0-9-]{10,}"),
    re.compile(r"AKIA[0-9A-Z]{16}"),
    re.compile(r"eyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{6,}"),
    re.compile(r"sk-[A-Za-z0-9_-]{20,}"),
    re.compile(r"-----BEGIN [A-Z ]*PRIVATE KEY-----.*?(-----END [A-Z ]*PRIVATE KEY-----|\Z)", re.S),
]
# password=..., api_key: "..." 처럼 이름으로 드러나는 값. 값이 실제 비밀값의 모양일 때만 가린다 —
# 예제 코드(os.environ["TOKEN"]), 자리 표시(<token>, ${VAR}), 경로, 비밀의 "이름"은 답변에 필요하므로 그대로 둔다.
_ASSIGN = re.compile(
    r"(?i)\b([a-z0-9_.-]*(?:password|passwd|secret|token|api[_-]?key|private[_-]?key)[a-z0-9_.-]*)"
    r"""(["']?\s*[:=]\s*["']?)([^\s"'`,;]{8,})"""
)
_SECRET_ALPHABET = re.compile(r"[A-Za-z0-9+/=_~-]+\Z")
_PLACEHOLDER = re.compile(r"(?i)xxx|your|example|changeme|placeholder|redacted|dummy|todo|sample|test")
# 값이 비밀이 아니라 그 이름·종류·위치를 가리키는 키 (secret_name, token_type, password_file, …)
_REFERENCE_KEY = re.compile(
    r"(?i)(name|type|path|file|dir|id|env|url|uri|endpoint|ttl|expiry|expires|length|size|mode|header|prefix|"
    r"version|label|field|key_id)s?\Z"
)


def redact(text: str):
    """(가린 텍스트, 가린 곳 수)"""
    count = 0

    def token(_m):
        nonlocal count
        count += 1
        return MASK

    for pat in _TOKENS:
        text = pat.sub(token, text)

    def assign(m):
        nonlocal count
        key, sep, value = m.group(1), m.group(2), m.group(3)
        if not _looks_like_secret(key, value):
            return m.group(0)
        count += 1
        return key + sep + MASK

    return _ASSIGN.sub(assign, text), count


def _looks_like_secret(key: str, value: str) -> bool:
    if _REFERENCE_KEY.search(key) or _PLACEHOLDER.search(value):
        return False
    if not _SECRET_ALPHABET.match(value):                 # 코드·경로·URN·${VAR}·<자리 표시> 는 다른 문자가 섞여 있다
        return False
    if re.fullmatch(r"[A-Z][A-Z0-9_]+", value):           # ENV_VAR_NAME
        return False
    # 실제 비밀값은 글자와 숫자가 섞여 있다. 단어만 이어 붙인 값(설명·식별자)은 가리지 않는다.
    return bool(re.search(r"[0-9]", value) and re.search(r"[A-Za-z]", value))
