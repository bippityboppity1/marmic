"""Driving journeys — the option no airline will ever quote you.

For a family living in Puglia, the honest answer to "where should we go" is
frequently a two-hour drive, and a tool that only speaks IATA cannot say so.
This provider prices a road journey the only way a road journey can be
priced: distance and time from a routing engine, multiplied by what fuel and
tolls actually cost.

That makes every number here an **estimate**, never a fare. Nobody sells you
a drive. The cost model is deliberately visible and adjustable rather than
hidden behind a single figure — see the TRAVELAGENT_FUEL_* settings — because
a driving cost you cannot argue with is a driving cost you should not trust.

Two public services do the work, both free and both used at human volumes:
  Nominatim  — place name to coordinates (OpenStreetMap)
  OSRM       — coordinates to a driving route
Neither needs a key. Both ask that you identify yourself and go easy, which
is why requests carry a User-Agent and the caller is one search at a time.
"""

from __future__ import annotations

from typing import Any

from ..errors import ContractMismatch, TravelAgentError
from ..models import Freshness, GroundQuote, Mode, Money
from ..query import GroundSearch
from ._util import utcnow
from .base import Provider

NOMINATIM_URL = "https://nominatim.openstreetmap.org/search"
OSRM_URL = "https://router.project-osrm.org/route/v1/driving"
USER_AGENT = "travelagent/0.1 (personal trip planning; +https://github.com/bippityboppity1/marmic)"

MOTORWAY_SHARE = 0.75
"""Fraction of a long drive assumed to be tolled motorway.

A guess, and labelled as one. Italian autostrada tolls are distance-based and
there is no free API for them, so the alternative to a stated assumption is a
silent one.
"""


class DriveProvider(Provider):
    name = "drive"
    supports_ground = True
    cacheable = True  # a road does not expire the way an offer does

    @property
    def configured(self) -> bool:
        return True  # no credentials exist to configure

    def setup_hint(self) -> str:
        return (
            "No key needed. Tune the cost model with TRAVELAGENT_FUEL_PRICE, "
            "TRAVELAGENT_FUEL_CONSUMPTION and TRAVELAGENT_TOLL_PER_KM."
        )

    async def _geocode(self, place: str, http) -> tuple[float, float]:
        payload = await http.request_json(
            "GET",
            NOMINATIM_URL,
            headers={"User-Agent": USER_AGENT, "Accept": "application/json"},
            params={"q": place, "format": "json", "limit": 1},
        )
        if not isinstance(payload, list) or not payload:
            raise TravelAgentError(f"could not find a place called {place!r}")
        first = payload[0]
        try:
            return float(first["lat"]), float(first["lon"])
        except (KeyError, TypeError, ValueError) as exc:
            raise ContractMismatch(
                "Nominatim returned a result without usable lat/lon"
            ) from exc

    async def search_ground(self, query: GroundSearch, http) -> list[GroundQuote]:
        origin = await self._geocode(query.origin, http)
        destination = await self._geocode(query.destination, http)

        coords = f"{origin[1]},{origin[0]};{destination[1]},{destination[0]}"
        payload = await http.request_json(
            "GET",
            f"{OSRM_URL}/{coords}",
            headers={"User-Agent": USER_AGENT},
            params={"overview": "false", "alternatives": "false"},
        )
        return [self._to_quote(payload, query)]

    def _to_quote(self, payload: Any, query: GroundSearch) -> GroundQuote:
        routes = (payload or {}).get("routes")
        if not isinstance(routes, list) or not routes:
            raise ContractMismatch(
                "OSRM returned no 'routes' — the routing contract may have moved"
            )
        route = routes[0]
        try:
            km = float(route["distance"]) / 1000.0
            minutes = int(round(float(route["duration"]) / 60.0))
        except (KeyError, TypeError, ValueError) as exc:
            raise ContractMismatch(
                "OSRM route lacked numeric 'distance'/'duration'"
            ) from exc

        breakdown, notes = self._cost(km, query.currency)
        total = sum((m.amount for m in breakdown.values()), start=Money.of(0, query.currency).amount)

        return GroundQuote(
            provider=self.name,
            mode=Mode.DRIVE,
            origin=query.origin,
            destination=query.destination,
            distance_km=round(km, 1),
            duration_minutes=minutes,
            price=Money.of(total, query.currency),
            freshness=Freshness.ESTIMATE,
            bookable=False,
            cost_breakdown=breakdown,
            notes=notes,
            observed_at=utcnow(),
            raw={"route": route},
        )

    def _cost(self, km: float, currency: str) -> tuple[dict[str, Money], list[str]]:
        cfg = self.config
        litres = km / 100.0 * cfg.fuel_consumption_l_per_100km
        fuel = litres * cfg.fuel_price_per_litre
        tolls = km * MOTORWAY_SHARE * cfg.toll_per_km

        breakdown = {
            "fuel": Money.of(round(fuel, 2), currency),
            "tolls": Money.of(round(tolls, 2), currency),
        }
        notes = [
            f"One way. Fuel at {cfg.fuel_price_per_litre:.2f} {currency}/L and "
            f"{cfg.fuel_consumption_l_per_100km:.1f} L/100km.",
            f"Tolls assume {int(MOTORWAY_SHARE * 100)}% motorway at "
            f"{cfg.toll_per_km:.2f} {currency}/km — an assumption, not a quote.",
            "Excludes parking, wear and any ferry crossing.",
        ]
        return breakdown, notes

    async def probe(self, http) -> str:
        payload = await http.request_json(
            "GET",
            f"{OSRM_URL}/16.6,41.1;16.9,40.6",
            headers={"User-Agent": USER_AGENT},
            params={"overview": "false"},
        )
        routes = (payload or {}).get("routes") or []
        return f"routing engine reachable ({len(routes)} route)"
