---
name: live-travel-prices
description: Fetch real flight and hotel prices from live sources during trip planning, and present them with their provenance intact. Use whenever a trip conversation needs an actual number — "how much is a flight to X", "what will this cost", "is that a good price", budgeting a trip, comparing dates or airports, or checking a fare the user was quoted elsewhere. Complements the trip-planning skill, which owns routing and itinerary logic; this one owns the numbers.
---

# Live travel prices

Real prices come from the `travelagent` MCP server. If those tools are not
available, say so plainly and fall back to ranges — do not invent a number
and do not present a remembered fare as a current one.

## The rule that replaces "never quote a price"

Prices now arrive labelled, and the label decides the language:

| Label in the table | What it is | How to say it |
| --- | --- | --- |
| **bookable** | Live inventory, sellable at that price right now | "184 EUR" — state it as a price |
| `cached` | A real fare seen recently, may already be gone | "around 96 EUR" — a range or an indication |
| `est.` | Historical or modelled | "typically 90–140" — a planning range only |

Never promote a label. A cached 96 does not become "96 EUR" because it would
make the budget work. When the cheapest number is cached and the cheapest
bookable one is higher, give both and say which is which — that gap is
exactly the information the user needs.

Prices carry a read timestamp. If a number is more than an hour old in the
conversation, re-run the search rather than repeating it.

## Order of operations

1. **`cheapest_dates` first, whenever dates are flexible.** Moving the
   departure by a day or two beats almost every other lever. Establish the
   shape of the month before pricing a specific date.
2. **`search_flights` for the real candidates.** Use `flexible_days` to
   sweep nearby dates in one call. Route decisions come from the
   trip-planning skill; this tool prices them.
3. **`search_hotels` once the base and dates are fixed.** Neighborhood
   first, property second — a cheap room in the wrong area is not cheap.
4. **`provider_status` when something returns nothing.**

## Reading the results

The table already ranks by price and flags the rest. Two things it surfaces
that matter more than the headline number:

- **Cheapest vs best value.** When these differ, the tool says so. The gap
  usually buys back hours or removes a fragile connection — present the
  trade, don't just report the minimum.
- **Warnings.** Connections under an hour and separate-ticket itineraries
  are called out. Repeat those to the user every time. A 35-minute
  connection is the single most common way a plan fails, and a self-transfer
  has no protection when the first leg slips.

Quote the checked-bag line when it exists. Headline fares mislead most on
baggage.

## When a search comes back empty

Empty is ambiguous and the two meanings are opposite:

- **No sources configured** — nothing was searched. This says nothing about
  the route. Tell the user to run `travelagent doctor` and add a token.
- **Sources ran and found nothing** — that is a real signal about the route
  or the date, and it usually means the route needs a connection the tool
  was not asked for, or the date is outside the schedule.

The tools distinguish these in their output. Never collapse them into "no
flights found".

## Presenting

Lead with the decision, not the data. A price table is an input to a choice;
say what it implies:

> **Fly out on the 16th, not the 15th** — same airline, 88 EUR cheaper, and
> it buys a full extra afternoon in Naples. The 15th only wins if the
> Thursday meeting is immovable.

Then the table. Then the single next decision that unblocks everything else.

## What not to do

- Don't re-run identical searches inside one turn. Results are cached for 15
  minutes; a repeat call returns the same numbers and burns quota.
- Don't quote a fare without saying when it was read.
- Don't present a scraped price as bookable. Google Flights prices are live
  readings, not offers — the tool marks them accordingly.
- Don't fill a budget line with a number the tools didn't return. Say the
  line is unpriced and why.
