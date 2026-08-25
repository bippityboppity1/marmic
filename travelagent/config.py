"""Configuration: credentials from the environment, sane defaults."""

from __future__ import annotations

import os
from dataclasses import dataclass, field
from pathlib import Path


def _env(*names: str) -> str | None:
    for n in names:
        v = os.environ.get(n)
        if v and v.strip():
            return v.strip()
    return None


def _bool(name: str, default: bool = False) -> bool:
    raw = os.environ.get(name)
    if raw is None:
        return default
    return raw.strip().lower() in {"1", "true", "yes", "on"}


def _load_dotenv(path: Path) -> None:
    """Minimal .env loader so the CLI works without extra dependencies.

    Existing environment variables always win.
    """
    if not path.is_file():
        return
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        key, val = key.strip(), val.strip().strip("'\"")
        os.environ.setdefault(key, val)


def _dotenv_path() -> Path:
    """Which .env to read.

    Defaults to the one in the working directory, which is right for the CLI.
    The MCP server is the exception: Claude Code starts it from whatever
    directory it pleases, so it would never find the repo's .env and its
    tokens would have to be duplicated into the MCP config in plaintext.
    TRAVELAGENT_DOTENV points it at the file instead, keeping the secret in
    one gitignored place.
    """
    explicit = os.environ.get("TRAVELAGENT_DOTENV")
    if explicit and explicit.strip():
        return Path(explicit.strip())
    return Path.cwd() / ".env"


@dataclass
class Config:
    duffel_token: str | None = None
    travelpayouts_token: str | None = None
    travelpayouts_marker: str | None = None

    currency: str = "EUR"
    market: str = "IT"
    locale: str = "en-GB"
    home_airports: list[str] = field(default_factory=list)

    timeout_seconds: float = 30.0
    max_retries: int = 2
    cache_ttl_seconds: int = 900
    cache_path: Path = field(
        default_factory=lambda: Path.home() / ".cache" / "travelagent" / "prices.sqlite3"
    )
    cache_enabled: bool = True

    scraping_enabled: bool = False
    scraper_headless: bool = True
    scraper_debug_dir: Path | None = None
    scraper_min_interval_seconds: float = 4.0

    @classmethod
    def from_env(cls, dotenv: Path | None = None) -> Config:
        _load_dotenv(dotenv or _dotenv_path())
        home = _env("TRAVELAGENT_HOME_AIRPORTS") or ""
        cache = _env("TRAVELAGENT_CACHE_PATH")
        debug_dir = _env("TRAVELAGENT_SCRAPER_DEBUG_DIR")
        return cls(
            duffel_token=_env("DUFFEL_ACCESS_TOKEN", "DUFFEL_TOKEN"),
            travelpayouts_token=_env("TRAVELPAYOUTS_TOKEN", "AVIASALES_TOKEN"),
            travelpayouts_marker=_env("TRAVELPAYOUTS_MARKER"),
            currency=(_env("TRAVELAGENT_CURRENCY") or "EUR").upper(),
            market=(_env("TRAVELAGENT_MARKET") or "IT").upper(),
            locale=_env("TRAVELAGENT_LOCALE") or "en-GB",
            home_airports=[a.strip().upper() for a in home.split(",") if a.strip()],
            timeout_seconds=float(_env("TRAVELAGENT_TIMEOUT") or 30.0),
            max_retries=int(_env("TRAVELAGENT_MAX_RETRIES") or 2),
            cache_ttl_seconds=int(_env("TRAVELAGENT_CACHE_TTL") or 900),
            cache_path=Path(cache) if cache else cls.__dataclass_fields__["cache_path"].default_factory(),  # type: ignore[misc]
            cache_enabled=_bool("TRAVELAGENT_CACHE", True),
            scraping_enabled=_bool("TRAVELAGENT_SCRAPING", False),
            scraper_headless=_bool("TRAVELAGENT_SCRAPER_HEADLESS", True),
            scraper_debug_dir=Path(debug_dir) if debug_dir else None,
            scraper_min_interval_seconds=float(
                _env("TRAVELAGENT_SCRAPER_INTERVAL") or 4.0
            ),
        )
