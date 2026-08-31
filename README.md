# marmic

Personal repo. Contains **travelagent** — the live-pricing layer for trip planning
with Claude.

---

# travelagent

Real flight and hotel prices, with their provenance attached.

The `trip-planning` skill is good at the part that actually breaks trips: routing,
pacing, connection risk, what's closed on a Monday. What it could not do was get a
real number. It said so in its own opening rule — *"live fares and availability
cannot be retrieved"* — and every price it produced was a range.

This package retrieves them, and enforces the rest of that rule in the type system
rather than in prose.

## The idea: every price knows what it is

One field does most of the work here.

| Freshness | Meaning | Cacheable | Quotable |
| --- | --- | --- | --- |
| `LIVE` + `bookable` | Live inventory, sellable at this price now | no | **yes** |
| `LIVE` | Read from a live page, but we can't sell it | no | as a current reading |
| `CACHED` | A real fare someone saw recently, may be gone | yes | as a range |
| `ESTIMATE` | Historical or modelled | yes | as a planning range |

That single distinction drives three behaviours that would otherwise need
discipline to get right:

- **Dedupe** prefers a bookable offer over a cheaper cached sighting. A price you
  cannot buy is not a better price.
- **Caching** never stores a live offer. Duffel offers expire; re-serving one an
  hour later would quote a dead price as a current one.
- **Rendering** puts the label in the table, next to the number, not in a footnote
  under it.

## Install

```bash
pip install -e .          # core
pip install -e '.[mcp]'   # + the MCP server Claude talks to
pip install -e '.[all]'   # + browser-driven price reading
```

Full step-by-step: **docs/SETUP.md** (macOS/Linux) or
**docs/SETUP-WINDOWS.md** (Windows). In short:

```bash
cp .env.example .env      # add at least one token
travelagent doctor        # says exactly what is live and what is missing
```

**Run `doctor` first.** It is the fastest way to tell a missing API key apart from
a broken route, and it exercises each provider's real contract.

## Price sources

| Source | Gives you | Cost | Notes |
| --- | --- | --- | --- |
| **Duffel** | Bookable fares, 300+ airlines | Free test tier | The only source whose numbers are quotable as prices |
| **Travelpayouts** | Cached fares + month price calendar | Free | Powers `cheapest_dates` — usually the biggest single saving |
| **Hotellook** | Cached hotel prices | Free (token lifts rate limit) | Prices only; says nothing about availability |
| **Google Flights** | Live fare readings | Free | Browser-driven, off by default |
| **Booking.com** | Live hotel readings | Free | Browser-driven, off by default |

**Amadeus is not in this list.** Its Self-Service tier shut down on 17 July 2026 and
existing self-service keys were disabled — if you find a tutorial recommending it,
it predates the shutdown. Duffel is the practical replacement: free test tokens,
no accreditation.

## Using it

### From Claude (the point of the exercise)

```bash
claude mcp add travelagent -- travelagent-mcp
```

Four tools appear: `search_flights`, `search_hotels`, `cheapest_dates`,
`provider_status`. The `live-travel-prices` skill in `.claude/skills/` tells Claude
how to use them and, more importantly, how to talk about the results — see
`docs/trip-planning-updates.md` for the two edits that bring your existing
`trip-planning` skill into line.

### From the shell

```bash
# Cheapest departure dates across a month — do this before pricing a date
travelagent calendar NAP LHR --month 2026-10

# Fares, sweeping three days either side
travelagent flights NAP LHR --depart 2026-10-15 --return 2026-10-22 --flex 3

# Stays
travelagent hotels Naples --checkin 2026-10-15 --checkout 2026-10-18 --min-stars 3

# By road — the option no airline will quote you
travelagent drive Giovinazzo Matera

travelagent doctor
travelagent cache --clear
```

Add `--json` for machine-readable output.

## Ground journeys

`drive` prices a road trip from a routing engine plus a fuel-and-tolls model.
Every number it returns is an **estimate** and never bookable, because nobody
sells you a drive — the cost model is printed alongside the total so it can be
argued with, and tuned via `TRAVELAGENT_FUEL_PRICE`,
`TRAVELAGENT_FUEL_CONSUMPTION` and `TRAVELAGENT_TOLL_PER_KM`.

Rail and ferry are modelled in the types (`Mode.RAIL`, `Mode.FERRY`) but have no
adapter yet. Neither Trenitalia nor the Adriatic ferry operators publish a usable
free API, so both would need either a paid data provider or a scraper, and a
half-built adapter that silently returns nothing is worse than an honest gap.

## What it flags without being asked

The table is ranked by price, but price is rarely the decision:

- **Cheapest vs best value**, when they differ. Time and stops are priced against
  the *fastest* option on the table, not in the abstract — so eight extra hours
  costs real money in the ranking instead of a rounding error.
- **Connections under an hour.** The most common way a plan fails.
- **Separate-ticket itineraries**, which have no protection when leg one slips.
- **Checked baggage**, where headline fares mislead most.

## Browser-driven reading

Off by default; `TRAVELAGENT_SCRAPING=1` turns it on.

Automated collection sits against most travel sites' terms of service, and they
defend it with rate limits and captchas. This path is throttled to one page at a
time with a deliberate pause, and is meant for reading prices for your own trip at
human volumes. If a site blocks it, use the site — don't route around the block.
The API providers are the supported path; this is the fallback.

Selectors move. Both scrapers parse the most stable thing each page offers —
accessibility labels on Google Flights, `data-testid` attributes on Booking.com —
and dump HTML plus a screenshot to `TRAVELAGENT_SCRAPER_DEBUG_DIR` when a selector
stops matching, so a break is a five-minute fix.

## Adding a source

One file in `travelagent/providers/`, subclass `Provider`, register it in
`registry.py`. Implement `configured`, `setup_hint()`, `search_flights()` or
`search_hotels()`, and a cheap `probe()` so it shows up in `doctor`. Set
`cacheable = False` if its prices are live and perishable. Nothing else changes.

## Tests

```bash
pytest        # 94 tests
ruff check .
```

Every provider is pinned against a recorded response in `tests/fixtures/`. That is
deliberate: it means a provider changing its response shape fails a test loudly
instead of the tool quietly returning nothing, which is the failure mode that
matters — an empty result and a missing API key look identical in a table and mean
opposite things.

## Known limitation: the contracts are unverified against live APIs

This was built in an environment whose egress policy blocks travel APIs, so **no
request has ever been made to Duffel, Travelpayouts or Hotellook from here.** The
request and response shapes come from each provider's published documentation as of
August 2026 and are pinned by fixtures, but a field name could still be wrong.

`travelagent doctor` exists for exactly this. It makes the cheapest real call each
provider offers and reports what came back, so the first run tells you in seconds
whether a contract needs a one-line correction. The parsers raise
`ContractMismatch` with a specific message rather than returning an empty list, so
a moved contract announces itself.
