"""The provider contract.

Adding a source means writing one adapter here and registering it. Nothing
else in the codebase needs to change.
"""

from __future__ import annotations

import abc
from typing import TYPE_CHECKING

from ..models import FlightQuote, HotelQuote
from ..query import FlightSearch, HotelSearch

if TYPE_CHECKING:
    from ..config import Config
    from ..http import HttpClient


class Provider(abc.ABC):
    name: str = "provider"
    supports_flights: bool = False
    supports_hotels: bool = False
    is_scraper: bool = False
    cacheable: bool = True
    """False for sources whose prices are live and perishable.

    Caching a live bookable offer and re-serving it later would present a
    dead price as a current one, which is the exact failure this whole
    package exists to prevent.
    """

    def __init__(self, config: Config) -> None:
        self.config = config

    @property
    @abc.abstractmethod
    def configured(self) -> bool:
        """True when this provider has what it needs to run."""

    @abc.abstractmethod
    def setup_hint(self) -> str:
        """One line telling the user how to turn this provider on."""

    async def search_flights(
        self, query: FlightSearch, http: HttpClient
    ) -> list[FlightQuote]:
        raise NotImplementedError

    async def search_hotels(
        self, query: HotelSearch, http: HttpClient
    ) -> list[HotelQuote]:
        raise NotImplementedError

    async def probe(self, http: HttpClient) -> str:
        """Cheapest call that proves credentials and contract are good.

        Used by `travelagent doctor`. Returns a human-readable status line.
        """
        return "no probe implemented"
