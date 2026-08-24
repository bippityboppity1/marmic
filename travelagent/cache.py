"""TTL cache on sqlite.

Live price lookups are metered and slow. A planning conversation asks the
same question repeatedly ("what about the 14th?"), so short-TTL caching keeps
the session fast and the quota intact. TTL is deliberately short — a stale
price is worse than a slow one.
"""

from __future__ import annotations

import hashlib
import json
import sqlite3
import time
from pathlib import Path
from typing import Any

_SCHEMA = """
CREATE TABLE IF NOT EXISTS cache (
    key TEXT PRIMARY KEY,
    provider TEXT NOT NULL,
    payload TEXT NOT NULL,
    stored_at REAL NOT NULL,
    expires_at REAL NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_cache_expiry ON cache (expires_at);
"""


def cache_key(provider: str, payload: dict[str, Any]) -> str:
    blob = json.dumps(payload, sort_keys=True, default=str)
    digest = hashlib.sha256(blob.encode("utf-8")).hexdigest()[:32]
    return f"{provider}:{digest}"


class PriceCache:
    def __init__(self, path: Path, ttl_seconds: int = 900, enabled: bool = True) -> None:
        self.path = path
        self.ttl = ttl_seconds
        self.enabled = enabled
        self._conn: sqlite3.Connection | None = None

    def _connect(self) -> sqlite3.Connection:
        if self._conn is None:
            self.path.parent.mkdir(parents=True, exist_ok=True)
            self._conn = sqlite3.connect(self.path)
            self._conn.executescript(_SCHEMA)
            self._conn.commit()
        return self._conn

    def get(self, key: str) -> Any | None:
        if not self.enabled:
            return None
        conn = self._connect()
        row = conn.execute(
            "SELECT payload, expires_at FROM cache WHERE key = ?", (key,)
        ).fetchone()
        if row is None:
            return None
        payload, expires_at = row
        if expires_at < time.time():
            conn.execute("DELETE FROM cache WHERE key = ?", (key,))
            conn.commit()
            return None
        return json.loads(payload)

    def set(self, key: str, provider: str, value: Any, ttl: int | None = None) -> None:
        if not self.enabled:
            return
        conn = self._connect()
        now = time.time()
        conn.execute(
            "INSERT OR REPLACE INTO cache (key, provider, payload, stored_at, expires_at)"
            " VALUES (?, ?, ?, ?, ?)",
            (key, provider, json.dumps(value, default=str), now, now + (ttl or self.ttl)),
        )
        conn.commit()

    def purge_expired(self) -> int:
        conn = self._connect()
        cur = conn.execute("DELETE FROM cache WHERE expires_at < ?", (time.time(),))
        conn.commit()
        return cur.rowcount

    def clear(self) -> None:
        conn = self._connect()
        conn.execute("DELETE FROM cache")
        conn.commit()

    def close(self) -> None:
        if self._conn:
            self._conn.close()
            self._conn = None
