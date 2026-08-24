# Updating your `trip-planning` skill

Your existing skill opens with a rule that is now half wrong:

> **Never present estimated prices as if they were quotes.** Live fares and
> availability cannot be retrieved.

The second sentence no longer holds. Two edits bring it in line without
touching anything else — the routing, itinerary and pushback sections are
unaffected and remain the more valuable half of that skill.

Your skill is synced from your Claude account, so make these edits wherever
you manage it (the copy in this repo's `.claude/skills/` is not the same
file).

---

## Edit 1 — replace the opening rule

**Find:**

```markdown
## The one rule that matters

**Never present estimated prices as if they were quotes.** Live fares and availability
cannot be retrieved. Every number is a range with a stated basis (typical shoulder-season
economy fare, published rack rate, historical average). Label them. The user books
elsewhere; this skill's job is to make sure what they book is *the right thing*.
```

**Replace with:**

```markdown
## The one rule that matters

**Never present a price as more certain than its source.** Live fares can now be
retrieved — use the `travelagent` MCP tools, and see the `live-travel-prices` skill for
how to read them. A fare the tools label **bookable** is live inventory and may be stated
as a price. Everything else — cached fares, rack rates, historical averages — stays a
range with its basis named. Label every number, always.

If the pricing tools are unavailable, say so and fall back to ranges. Never fill the gap
with a remembered fare.

The failure mode of most trip plans is not overpaying. It is a Day 3 that is physically
impossible, a museum closed on Mondays, or a connection with 55 minutes in Fiumicino.
Optimize for that.
```

*(The last paragraph is unchanged — it just moves under the new rule.)*

---

## Edit 2 — put the tools ahead of web search in `## Sourcing`

**Find the first line of that section:**

```markdown
Search the open web for: whether a direct route exists, published seasonal fare ranges,
```

**Insert immediately above it:**

```markdown
**Prices come from the `travelagent` tools first, not the open web.** Call
`cheapest_dates` when dates are flexible, then `search_flights` and `search_hotels`. Web
search is for everything the tools do not cover: whether a route exists at all, opening
hours, visa rules, seasonal closures.
```

---

## Optional — tighten the budget section

In `### 4. Budget`, the line "Give a range per line, state the basis" can become:

```markdown
Give a range per line and state the basis — cite live prices as prices and cached ones as
ranges, exactly as the tools label them. Mark any line the tools could not price as
unpriced rather than guessing it.
```

---

## What you do not need to change

The `Departure geography` section already does the work that matters most for a thin
regional home airport, and it now has real numbers behind it: `search_flights` accepts any
origin, so pricing the positioning option (drive to a bigger hub, fly direct) against the
connecting itinerary from home is a two-call comparison rather than an assertion.
