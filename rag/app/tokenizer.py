"""hybrid 검색용 토크나이저. 문서와 질문에 같은 함수를 써야 한다 (envector SDK 는 토큰화를 하지 않는다)."""
import re
import unicodedata

LABEL = "ko-en-id-v1"

_WORD = re.compile(r"[a-z0-9][a-z0-9_.\-]*[a-z0-9]|[a-z0-9]|[가-힣]+")
_SPLIT = re.compile(r"[_.\-]")
_JOSA = re.compile(r"(으로|에서|에게|까지|부터|이랑|랑|하려면|하면|했는데|은|는|이|가|을|를|에|의|로|와|과|도|만)$")
STOPWORDS = frozenset(
    "a an and are as at be but by for if in into is it no not of on or such that the their then there "
    "these they this to was will with".split()
)


def tokenize(text: str):
    """NFC + 소문자. 식별자는 통째로 넣고 _ . - 로 쪼갠 조각도 함께 넣는다 (fs.aio-max-nr → 전체 + fs, aio, max, nr)."""
    text = unicodedata.normalize("NFC", text or "").lower()
    out = []
    for word in _WORD.findall(text):
        if "가" <= word[0] <= "힣":
            stem = _JOSA.sub("", word)
            out.append(stem if len(stem) > 1 else word)
            continue
        if word not in STOPWORDS:
            out.append(word)
        parts = [p for p in _SPLIT.split(word) if p]
        if len(parts) > 1:
            out.extend(p for p in parts if p not in STOPWORDS)
    return out
