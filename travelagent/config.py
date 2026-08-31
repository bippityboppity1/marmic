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


_DOTENV_WROTE: dict[str, str] = {}
"""What this loader last put into os.environ, per key.

Needed to tell our own writes apart from real environment variables. With
setdefault they are indistinguishable a moment later, so the file is
effectively read once per process — and a process that outlives an edit to
.env keeps serving the old value. The MCP server is exactly that process:
rotating a token in .env would never reach it.
"""


def _load_dotenv(path: Path) -> None:
    """Minimal .env loader so the CLI works without extra dependencies.

    A real environment variable always wins over the file. A value this
    loader wrote on an earlier pass is not a real environment variable, so it
    is refreshed rather than defended, and a long-lived process picks up an
    edited .env.
    """
    if not path.is_file():
        return

    seen: set[str] = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        line = line.strip()
        if not line or line.startswith("#") or "=" not in line:
            continue
        key, _, val = line.partition("=")
        key, val = key.strip(), val.strip().strip("'\"")
        seen.add(key)
        current = os.environ.get(key)
        if current is not None and current != _DOTENV_WROTE.get(key):
            continue  # someone else owns this value; leave it alone
        os.environ[key] = val
        _DOTENV_WROTE[key] = val

    # A key we set that has since been removed from the file should not
    # linger in the environment pretending to still be configured.
    for stale in set(_DOTENV_WROTE) - seen:
        if os.environ.get(stale) == _DOTENV_WROTE[stale]:
            os.environ.pop(stale, None)
        _DOTENV_WROTE.pop(stale, None)


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

    fuel_price_per_litre: float = 1.75
    fuel_consumption_l_per_100km: float = 6.5
    toll_per_km: float = 0.08
    """Driving cost model. Defaults are Italian petrol and autostrada rates.

    Exposed rather than buried because a driving cost is arithmetic, not a
    quote, and arithmetic you cannot see is arithmetic you cannot check.
    """

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
            fuel_price_per_litre=float(_env("TRAVELAGENT_FUEL_PRICE") or 1.75),
            fuel_consumption_l_per_100km=float(
                _env("TRAVELAGENT_FUEL_CONSUMPTION") or 6.5
            ),
            toll_per_km=float(_env("TRAVELAGENT_TOLL_PER_KM") or 0.08),
            scraping_enabled=_bool("TRAVELAGENT_SCRAPING", False),
            scraper_headless=_bool("TRAVELAGENT_SCRAPER_HEADLESS", True),
            scraper_debug_dir=Path(debug_dir) if debug_dir else None,
            scraper_min_interval_seconds=float(
                _env("TRAVELAGENT_SCRAPER_INTERVAL") or 4.0
            ),
        )
