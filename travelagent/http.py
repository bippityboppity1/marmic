"""Shared async HTTP with retries, backoff and honest error mapping."""

from __future__ import annotations

import asyncio
import random
from typing import Any

import httpx

from .errors import Blocked, ProviderTimeout, RateLimited, TravelAgentError

RETRY_STATUS = {429, 500, 502, 503, 504}
USER_AGENT = "travelagent/0.1 (+personal trip planning)"


class HttpClient:
    """Thin wrapper over httpx.AsyncClient.

    Kept deliberately small: providers own their contracts, this owns
    transport concerns only.
    """

    def __init__(self, timeout: float = 30.0, max_retries: int = 2) -> None:
        self._timeout = timeout
        self._max_retries = max_retries
        self._client: httpx.AsyncClient | None = None

    async def __aenter__(self) -> HttpClient:
        self._client = httpx.AsyncClient(
            timeout=httpx.Timeout(self._timeout),
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
            follow_redirects=True,
        )
        return self

    async def __aexit__(self, *exc: Any) -> None:
        if self._client:
            await self._client.aclose()
            self._client = None

    async def request_json(
        self,
        method: str,
        url: str,
        *,
        headers: dict[str, str] | None = None,
        params: dict[str, Any] | None = None,
        json: Any = None,
    ) -> Any:
        if self._client is None:
            raise TravelAgentError("HttpClient used outside its context manager")

        last_exc: Exception | None = None
        for attempt in range(self._max_retries + 1):
            try:
                resp = await self._client.request(
                    method, url, headers=headers, params=params, json=json
                )
            except httpx.TimeoutException as exc:
                last_exc = ProviderTimeout(f"timed out after {self._timeout}s")
                if attempt >= self._max_retries:
                    raise last_exc from exc
            except httpx.ProxyError as exc:
                # The sandbox egress proxy denies non-allowlisted hosts.
                raise Blocked(f"egress blocked reaching {url}: {exc}") from exc
            except httpx.HTTPError as exc:
                last_exc = TravelAgentError(f"transport error: {exc}")
                if attempt >= self._max_retries:
                    raise last_exc from exc
            else:
                if resp.status_code == 429:
                    if attempt >= self._max_retries:
                        raise RateLimited(_rate_limit_detail(resp))
                elif resp.status_code in (401, 403):
                    raise Blocked(
                        f"{resp.status_code} from provider — check credentials "
                        f"or access tier: {resp.text[:200]}"
                    )
                elif resp.status_code not in RETRY_STATUS:
                    resp.raise_for_status()
                    if not resp.content:
                        return None
                    return resp.json()
                elif attempt >= self._max_retries:
                    raise TravelAgentError(
                        f"{resp.status_code} from provider: {resp.text[:200]}"
                    )

            await asyncio.sleep(_backoff(attempt))

        raise last_exc or TravelAgentError("request failed")


def _backoff(attempt: int) -> float:
    """Exponential with jitter, so parallel providers don't resonate."""
    return min(2.0**attempt, 8.0) + random.uniform(0, 0.4)


def _rate_limit_detail(resp: httpx.Response) -> str:
    limit = resp.headers.get("X-Ratelimit-Limit")
    interval = resp.headers.get("X-Ratelimit-Interval")
    if limit:
        return f"rate limited ({limit} per {interval or '?'}s)"
    retry_after = resp.headers.get("Retry-After")
    return f"rate limited (retry after {retry_after or 'unknown'})"
