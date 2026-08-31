"""Ground journeys: routing contract, cost model, and honest labelling."""

from __future__ import annotations

import pytest

from travelagent.errors import ContractMismatch, TravelAgentError
from travelagent.models import Freshness, Mode
from travelagent.providers.ground import DriveProvider
from travelagent.query import GroundSearch

from .conftest import FakeHttp

GEO_GIOVINAZZO = [{"lat": "41.1876", "lon": "16.6708", "display_name": "Giovinazzo"}]
GEO_MATERA = [{"lat": "40.6664", "lon": "16.6043", "display_name": "Matera"}]
ROUTE = {"routes": [{"distance": 104_000.0, "duration": 6900.0}], "code": "Ok"}


@pytest.fixture
def journey():
    return GroundSearch(origin="Giovinazzo", destination="Matera")


async def test_drive_parses_a_route(config, journey):
    http = FakeHttp(GEO_GIOVINAZZO, GEO_MATERA, ROUTE)
    quote = (await DriveProvider(config).search_ground(journey, http))[0]

    assert quote.mode is Mode.DRIVE
    assert quote.distance_km == 104.0
    assert quote.duration_minutes == 115


async def test_a_drive_is_never_bookable(config, journey):
    """Nobody sells you a drive. The label has to say so."""
    http = FakeHttp(GEO_GIOVINAZZO, GEO_MATERA, ROUTE)
    quote = (await DriveProvider(config).search_ground(journey, http))[0]

    assert quote.freshness is Freshness.ESTIMATE
    assert quote.bookable is False
    assert quote.quotable is False


async def test_cost_is_itemised_so_it_can_be_argued_with(config, journey):
    http = FakeHttp(GEO_GIOVINAZZO, GEO_MATERA, ROUTE)
    quote = (await DriveProvider(config).search_ground(journey, http))[0]

    # 104 km at 6.5 L/100km and 1.75 EUR/L = 11.83; tolls 104 * 0.75 * 0.08 = 6.24
    assert float(quote.cost_breakdown["fuel"].amount) == pytest.approx(11.83, abs=0.05)
    assert float(quote.cost_breakdown["tolls"].amount) == pytest.approx(6.24, abs=0.05)
    assert float(quote.price.amount) == pytest.approx(18.07, abs=0.1)
    assert any("assumption" in n for n in quote.notes)


async def test_the_cost_model_is_configurable(config, journey):
    from dataclasses import replace

    thirsty = replace(config, fuel_consumption_l_per_100km=13.0, toll_per_km=0.0)
    http = FakeHttp(GEO_GIOVINAZZO, GEO_MATERA, ROUTE)
    quote = (await DriveProvider(thirsty).search_ground(journey, http))[0]

    assert float(quote.cost_breakdown["tolls"].amount) == 0.0
    assert float(quote.cost_breakdown["fuel"].amount) == pytest.approx(23.66, abs=0.1)


async def test_a_changed_routing_shape_is_loud(config, journey):
    http = FakeHttp(GEO_GIOVINAZZO, GEO_MATERA, {"code": "Ok"})
    with pytest.raises(ContractMismatch):
        await DriveProvider(config).search_ground(journey, http)


async def test_an_unknown_place_is_not_a_contract_bug(config, journey):
    http = FakeHttp([])
    with pytest.raises(TravelAgentError, match="could not find a place"):
        await DriveProvider(config).search_ground(journey, http)


def test_same_place_is_rejected():
    with pytest.raises(TravelAgentError):
        GroundSearch(origin="Bari", destination="bari")


def test_empty_endpoints_are_rejected():
    with pytest.raises(TravelAgentError):
        GroundSearch(origin="  ", destination="Matera")
