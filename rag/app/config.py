"""데이터 폴더와 소스 목록(sources.yml) 읽기."""
import os
from dataclasses import dataclass, field
from pathlib import Path

DEFAULT_MODEL = "BAAI/bge-m3"
DEFAULT_EXTENSIONS = (".md",)


def data_dir() -> Path:
    return Path(os.environ.get("RAG_DATA_DIR", "/data"))


@dataclass(frozen=True)
class Source:
    name: str
    repo: str = ""
    ref: str = "main"
    include: tuple = ()
    exclude: tuple = ()
    extensions: tuple = DEFAULT_EXTENSIONS
    backend: str = "local"
    index: str = ""
    mode: str = "vector"

    @property
    def dir(self) -> Path:
        return data_dir() / "corpus" / self.name


@dataclass(frozen=True)
class Settings:
    model: str = DEFAULT_MODEL
    sources: dict = field(default_factory=dict)


def parse_settings(raw: dict) -> Settings:
    raw = raw or {}
    sources = {}
    for name, cfg in (raw.get("sources") or {}).items():
        cfg = cfg or {}
        name = str(name).strip().lower()
        if not name or not name.replace("-", "").replace("_", "").isalnum():
            raise ValueError(f"소스 이름은 영문·숫자·-·_ 만 쓸 수 있다: {name!r}")
        backend = cfg.get("backend", "local")
        if backend not in ("local", "envector"):
            raise ValueError(f"{name}: backend 는 local 또는 envector 여야 한다: {backend!r}")
        mode = cfg.get("mode", "vector")
        if mode not in ("vector", "hybrid"):
            raise ValueError(f"{name}: mode 는 vector 또는 hybrid 여야 한다: {mode!r}")
        sources[name] = Source(
            name=name,
            repo=cfg.get("repo", ""),
            ref=cfg.get("ref", "main"),
            include=tuple(cfg.get("include") or ()),
            exclude=tuple(cfg.get("exclude") or ()),
            extensions=tuple(cfg.get("extensions") or DEFAULT_EXTENSIONS),
            backend=backend,
            index=cfg.get("index", ""),
            mode=mode,
        )
    return Settings(model=raw.get("model") or DEFAULT_MODEL, sources=sources)


def load_settings() -> Settings:
    import yaml

    path = data_dir() / "sources.yml"
    if not path.exists():
        return Settings()
    with open(path, encoding="utf-8") as f:
        return parse_settings(yaml.safe_load(f))
