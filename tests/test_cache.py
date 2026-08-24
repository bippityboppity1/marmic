"""Cache mechanics: TTL, isolation, and the off switch."""

from __future__ import annotations

from travelagent.cache import PriceCache, cache_key


def test_round_trips_a_value(tmp_path):
    cache = PriceCache(tmp_path / "c.sqlite3", ttl_seconds=60)
    cache.set("k", "prov", {"price": 184})
    assert cache.get("k") == {"price": 184}
    cache.close()


def test_expired_entries_are_not_served(tmp_path):
    cache = PriceCache(tmp_path / "c.sqlite3", ttl_seconds=60)
    cache.set("k", "prov", {"price": 184}, ttl=-1)
    assert cache.get("k") is None
    cache.close()


def test_disabled_cache_stores_nothing(tmp_path):
    cache = PriceCache(tmp_path / "c.sqlite3", ttl_seconds=60, enabled=False)
    cache.set("k", "prov", {"price": 184})
    assert cache.get("k") is None
    cache.close()


def test_missing_key_returns_none(tmp_path):
    cache = PriceCache(tmp_path / "c.sqlite3")
    assert cache.get("never-written") is None
    cache.close()


def test_survives_reopening(tmp_path):
    path = tmp_path / "c.sqlite3"
    first = PriceCache(path, ttl_seconds=60)
    first.set("k", "prov", [1, 2, 3])
    first.close()

    second = PriceCache(path, ttl_seconds=60)
    assert second.get("k") == [1, 2, 3]
    second.close()


def test_purge_and_clear(tmp_path):
    cache = PriceCache(tmp_path / "c.sqlite3", ttl_seconds=60)
    cache.set("live", "prov", 1)
    cache.set("dead", "prov", 2, ttl=-1)

    assert cache.purge_expired() == 1
    assert cache.get("live") == 1

    cache.clear()
    assert cache.get("live") is None
    cache.close()


def test_cache_key_is_stable_and_order_independent():
    a = cache_key("duffel", {"origin": "NAP", "destination": "LHR"})
    b = cache_key("duffel", {"destination": "LHR", "origin": "NAP"})
    assert a == b
    assert a.startswith("duffel:")


def test_cache_key_separates_providers_and_queries():
    base = {"origin": "NAP", "destination": "LHR"}
    assert cache_key("duffel", base) != cache_key("travelpayouts", base)
    assert cache_key("duffel", base) != cache_key("duffel", {**base, "destination": "CDG"})
