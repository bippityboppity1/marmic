"""Browser-driven price reading, for sources with no usable API.

A note worth reading once: automated collection sits against most travel
sites' terms of service, and they defend it with rate limits, captchas and IP
blocks. This path is off by default (TRAVELAGENT_SCRAPING=1 to enable), is
rate limited to one page at a time with a deliberate pause between requests,
and is meant for reading prices for your own trip at human volumes — not for
bulk collection. If a site blocks it, the answer is to use that site, not to
work around the block. Scrapers are the fallback; the API providers are the
supported path.

Selectors break. Every scraper here dumps HTML and a screenshot on a parse
failure (TRAVELAGENT_SCRAPER_DEBUG_DIR) so a broken selector is a five-minute
fix rather than a mystery.
"""

from __future__ import annotations

import asyncio
import re
import time
from pathlib import Path
from typing import Any

from ..errors import Blocked, TravelAgentError
from ..providers.base import Provider

BLOCK_MARKERS = (
    "unusual traffic",
    "are you a robot",
    "captcha",
    "recaptcha",
    "access denied",
    "verify you are human",
)

CONSENT_REJECT_RE = re.compile(
    r"^\s*(reject all|reject|rifiuta tutto|rifiuta|alle ablehnen|tout refuser|"
    r"rechazar todo|weiger alles)\s*$",
    re.IGNORECASE,
)
"""Buttons that decline a cookie-consent wall, across the locales this may load in.

Declining rather than accepting is deliberate: the prices are identical either
way, so there is no reason to opt someone's browser into ad personalisation to
read a flight time.
"""

_last_request_at: float = 0.0
_throttle_lock = asyncio.Lock()


async def _throttle(min_interval: float) -> None:
    """One request at a time, spaced out. Politeness and camouflage both."""
    global _last_request_at
    async with _throttle_lock:
        elapsed = time.monotonic() - _last_request_at
        if elapsed < min_interval:
            await asyncio.sleep(min_interval - elapsed)
        _last_request_at = time.monotonic()


class ScraperProvider(Provider):
    """Base for Playwright-driven providers."""

    is_scraper = True
    cacheable = False  # a scraped price is a live reading, not a record
    url_template: str = ""

    @property
    def configured(self) -> bool:
        return bool(self.config.scraping_enabled) and _playwright_available()

    def setup_hint(self) -> str:
        if not _playwright_available():
            return (
                "Install the browser path: pip install 'travelagent[scrape]' "
                "&& playwright install chromium"
            )
        return "Set TRAVELAGENT_SCRAPING=1 to enable browser-driven price reads."

    async def _render(self, url: str, wait_for: str, timeout_ms: int = 45_000) -> str:
        """Open a page, wait for results, return HTML.

        Raises Blocked on an anti-bot wall so the caller can degrade to API
        providers rather than reporting "no flights found", which would be a
        lie with real consequences for a plan.
        """
        try:
            from playwright.async_api import async_playwright
        except ImportError as exc:  # pragma: no cover - import guard
            raise TravelAgentError(
                "playwright is not installed; run: pip install 'travelagent[scrape]'"
            ) from exc

        await _throttle(self.config.scraper_min_interval_seconds)

        async with async_playwright() as pw:
            browser = await pw.chromium.launch(headless=self.config.scraper_headless)
            try:
                context = await browser.new_context(
                    locale=self.config.locale,
                    timezone_id="Europe/Rome",
                    viewport={"width": 1440, "height": 1000},
                    user_agent=(
                        "Mozilla/5.0 (Macintosh; Intel Mac OS X 10_15_7) "
                        "AppleWebKit/537.36 (KHTML, like Gecko) "
                        "Chrome/126.0.0.0 Safari/537.36"
                    ),
                )
                page = await context.new_page()
                await page.goto(url, wait_until="domcontentloaded", timeout=timeout_ms)
                await self._dismiss_consent(page)

                html_head = (await self._content(page))[:4000].lower()
                if any(m in html_head for m in BLOCK_MARKERS):
                    await self._dump(page, "blocked")
                    raise Blocked(f"{self.name} served an anti-bot challenge")

                try:
                    await page.wait_for_selector(wait_for, timeout=timeout_ms)
                except Exception:
                    await self._dump(page, "no-results")
                    raise TravelAgentError(
                        f"{self.name}: results selector {wait_for!r} never appeared. "
                        f"The page layout likely changed — see the debug dump."
                    ) from None

                # Let lazy price nodes settle before reading.
                await page.wait_for_timeout(2500)
                return await self._content(page)
            finally:
                await browser.close()

    @staticmethod
    async def _content(page: Any, attempts: int = 3) -> str:
        """page.content() during an in-flight navigation throws. Retry briefly."""
        last: Exception | None = None
        for _ in range(attempts):
            try:
                return await page.content()
            except Exception as exc:
                last = exc
                await page.wait_for_timeout(1000)
        raise TravelAgentError(f"could not read page content: {last}")

    async def _dismiss_consent(self, page: Any, timeout_ms: int = 6000) -> bool:
        """Clear a cookie-consent interstitial before looking for results.

        Google serves EU visitors a full-page "Before you continue" wall ahead
        of any result. Without this the scraper reads that page, finds no
        result nodes, and reports a layout change — a misleading diagnosis of
        a problem that is really one click.

        Returns True if a wall was dismissed. Absence is the normal case and
        never an error.
        """
        for locator in (
            page.get_by_role("button", name=CONSENT_REJECT_RE),
            page.locator('form button:has-text("Reject")'),
        ):
            try:
                await locator.first.click(timeout=timeout_ms)
            except Exception:
                continue
            # Dismissing the wall triggers a navigation. Let it finish, or the
            # next page.content() races it and throws mid-flight.
            for state in ("domcontentloaded", "networkidle"):
                try:
                    await page.wait_for_load_state(state, timeout=timeout_ms)
                except Exception:
                    pass
            await page.wait_for_timeout(1200)
            return True
        return False

    async def _dump(self, page: Any, reason: str) -> None:
        target = self.config.scraper_debug_dir
        if not target:
            return
        try:
            target = Path(target)
            target.mkdir(parents=True, exist_ok=True)
            stamp = time.strftime("%Y%m%d-%H%M%S")
            stem = target / f"{self.name}-{reason}-{stamp}"
            stem.with_suffix(".html").write_text(await page.content(), encoding="utf-8")
            await page.screenshot(path=str(stem.with_suffix(".png")), full_page=True)
        except Exception:  # pragma: no cover - debug aid must never mask the real error
            pass


def _playwright_available() -> bool:
    try:
        import playwright  # noqa: F401
    except ImportError:
        return False
    return True
