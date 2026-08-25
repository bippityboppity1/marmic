# Handoff to a local session

Context for a Claude Code session running **locally on the user's Windows PC**,
picking up work started in a cloud session.

## What this repo contains

`travelagent` — a Python package that fetches real flight and hotel prices and
attaches provenance to every number. It exists to fill the gap in the user's
`trip-planning` skill, which previously said "live fares cannot be retrieved" and
could only ever give ranges.

The central idea: every price carries a `Freshness` — `LIVE` + `bookable` (sellable
now, quotable as a price), `CACHED` (a real fare seen recently, may be gone), or
`ESTIMATE`. That drives dedupe (a bookable offer beats a cheaper cached sighting),
caching (live offers are never cached, since re-serving an expired offer quotes a
dead price) and rendering (the label sits in the table next to the number).

Sources: **Duffel** v2 (bookable fares), **Travelpayouts** (cached fares + month
price calendar), **Hotellook** (hotel prices), plus optional Playwright readers for
Google Flights and Booking.com that are off by default.

Interfaces: a CLI (`travelagent flights|hotels|calendar|doctor|cache`) and an MCP
server exposing `search_flights`, `search_hotels`, `cheapest_dates`,
`provider_status`.

State: 97 tests passing, ruff clean, all pushed to
`claude/travel-agent-realtime-prices-8ns6a3`.

## The one thing that is genuinely unverified

**No request has ever been made to a live travel API.** The cloud session's egress
policy blocked `api.duffel.com`, `api.travelpayouts.com` and
`engine.hotellook.com` (403 at the proxy), so every provider contract was written
from published documentation and pinned with recorded fixtures.

`travelagent doctor` makes the cheapest real call each provider offers. **Running it
successfully is the main goal of this session.** Parsers raise `ContractMismatch`
with a specific message rather than returning an empty list, so a wrong field name
announces itself rather than looking like an empty route.

## Where the user is

- **Windows**, PowerShell, not very experienced with the terminal — prefer running
  commands yourself over handing them a list to type.
- Repo probably cloned at `C:\Users\<user>\marmic`. Verify; it may be elsewhere.
- They created a Duffel **read+write** token already. That scope is fine — this
  package has no order, payment, or booking code, and its only `POST` is
  `/air/offer_requests`, which is the search itself.
- They got stuck somewhere around creating `.env` / running `doctor`. The exact
  error was never captured. Find out by running it.

## Windows specifics that already caused trouble

| Thing | Detail |
| --- | --- |
| Execution policy | `Activate.ps1` is blocked by default → `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` |
| Script location | Windows venvs use `.venv\Scripts\`, **not** `.venv/bin/` |
| MCP binary | `.venv\Scripts\travelagent-mcp.exe` — full path required |
| Extras quoting | `pip install -e ".[mcp]"` — PowerShell mangles unquoted brackets |
| Python | `python` may be Windows' Microsoft Store placeholder; real one via `winget install --id Python.Python.3.12 -e` |

## Duffel test vs live — set expectations before the first search

| Token prefix | Returns |
| --- | --- |
| `duffel_test_` | **Invented fares on real-looking airline codes** (IB, AA, BA, AZ, LX…) |
| `duffel_live_` | Real fares from 300+ real airlines |

Verified against the live sandbox on 2026-08-26: a test token does **not** return
one obviously fake carrier. NAP→LHR came back on IB, AA, BA, AZ, LX, SN and OS
alongside the fictional ZZ, at plausible prices and plausible times. There is no
visual tell. The package now marks these `sandbox` rather than `bookable` and
refuses to treat them as quotable — see `FlightQuote.sandbox`. Live access needs a
short review in the Duffel dashboard.

## What to do

1. `git pull` — the Windows guide and several fixes landed after the first clone.
2. Create the venv, activate it, `pip install -e ".[mcp]"`.
3. `Copy-Item .env.example .env`, get their Duffel token into `DUFFEL_ACCESS_TOKEN`.
   Do not print the token back to them.
4. Run `travelagent doctor`. Fix what it reports. This is the point of the session.
5. Once green: `travelagent calendar NAP LHR --month 2026-10` and
   `travelagent flights NAP LHR --depart 2026-10-15 --return 2026-10-22 --flex 3`.
6. Register the MCP server (`docs\SETUP-WINDOWS.md` Step 10) and install the skill
   (Step 11).

Full runbook: `docs\SETUP-WINDOWS.md`. Skill-side changes: `docs\trip-planning-updates.md`.

## If a provider contract is wrong

Fix the adapter in `travelagent\providers\`, update the matching fixture in
`tests\fixtures\`, keep the tests green, and commit to the same branch. The
adapters are deliberately small and isolated for exactly this.
