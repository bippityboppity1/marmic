# Running travelagent

**On Windows? Use [SETUP-WINDOWS.md](SETUP-WINDOWS.md) instead** — the commands
differ enough that translating them will cost you an afternoon.

Every command below is run on **your** machine, not in a Claude Code web session.
The web sandbox blocks outbound traffic to travel APIs, so live prices only work
locally.

---

## Step 0 — check Python

```bash
python3 --version    # need 3.11 or newer
```

If it prints 3.10 or lower, install a newer Python before continuing.

---

## Step 1 — get the code

```bash
git clone https://github.com/bippityboppity1/marmic.git
cd marmic
git checkout claude/travel-agent-realtime-prices-8ns6a3
```

Already cloned? `git pull` on that branch.

---

## Step 2 — install

```bash
python3 -m venv .venv
source .venv/bin/activate          # Windows: .venv\Scripts\activate
pip install -e '.[mcp]'
```

Add browser-driven price reading only if you want it (see Step 7):

```bash
pip install -e '.[all]' && playwright install chromium
```

**Check it worked:**

```bash
travelagent --help
```

You should see the five subcommands: `flights`, `hotels`, `calendar`, `doctor`, `cache`.

---

## Step 3 — get a Duffel token

This is the one that matters. Duffel is the only source that returns fares you can
quote as prices rather than as ranges.

1. Sign up at **https://duffel.com** → Dashboard → Developers → Access tokens
2. Create a token. You will get one of two kinds:

| Token | Returns | Use it for |
| --- | --- | --- |
| `duffel_test_...` | **Invented fares on real-looking airline codes** | Proving the plumbing works |
| `duffel_live_...` | Real fares from 300+ real airlines | Actual trip planning |

**Read that table again before you judge the results.** A test token returns
invented flights on an airline that does not exist. If your first search comes back
with one carrier and odd prices, the tool is working correctly and the token is the
limitation. Live tokens need a short review in the Duffel dashboard — request it
now, use the test token meanwhile.

---

## Step 4 — get a Travelpayouts token (optional but worth it)

Free, instant, no review. It powers `cheapest_dates` — the month-wide price
calendar — which is usually where the biggest saving is.

1. Sign up at **https://travelpayouts.com**
2. Dashboard → Developers → API tokens

---

## Step 5 — configure

```bash
cp .env.example .env
```

Open `.env` and fill in:

```
DUFFEL_ACCESS_TOKEN=duffel_test_...      # or duffel_live_...
TRAVELPAYOUTS_TOKEN=...                  # optional
TRAVELAGENT_CURRENCY=EUR
TRAVELAGENT_HOME_AIRPORTS=NAP,FCO        # your realistic departure set
```

`.env` is gitignored. It will not be committed.

---

## Step 6 — verify, before trusting anything

```bash
travelagent doctor
```

**This is the important step.** The contracts in this package were written from
published documentation and pinned by fixtures, but no request has ever been made
to a live API. `doctor` makes the cheapest real call each provider offers.

What you want:

```
| duffel        | ✅ ready | authenticated (test mode, airlines endpoint returned 1 row) |
| travelpayouts | ✅ ready | authenticated (LON->PAR probe returned 12 cached fares)      |
| hotellook     | ✅ ready | reachable (with token, Rome probe returned 1 properties)     |
```

If you see something else, the footer tells you which problem it is — a missing key
and an unreachable host need different fixes and it names them separately.

**If a provider reports `ContractMismatch`,** a field name in that adapter is wrong.
Send me the message; it is a one-line fix.

---

## Step 7 — first real searches

```bash
# Where is the month cheapest? Do this before pricing a specific date.
travelagent calendar NAP LHR --month 2026-10

# Fares, sweeping three days either side of the target
travelagent flights NAP LHR --depart 2026-10-15 --return 2026-10-22 --flex 3

# Stays
travelagent hotels Naples --checkin 2026-10-15 --checkout 2026-10-18 --min-stars 3
```

Read the **Basis** column. `bookable` is a real sellable price; `cached` is a fare
someone saw recently that may be gone. The tool will not blur them and neither
should you.

Optional browser-driven reading, if you installed it:

```bash
export TRAVELAGENT_SCRAPING=1
export TRAVELAGENT_SCRAPER_DEBUG_DIR=./scraper-debug
travelagent flights NAP LHR --depart 2026-10-15
```

Off by default on purpose. It is throttled, and it sits against most sites' terms
of service — fine for reading prices for your own trip, not for volume. If a site
blocks it, use the site.

---

## Step 8 — connect it to Claude

This is the point of the whole thing: prices inside a planning conversation.

```bash
claude mcp add travelagent -s user \
  -e DUFFEL_ACCESS_TOKEN=duffel_test_... \
  -e TRAVELPAYOUTS_TOKEN=... \
  -- /FULL/PATH/TO/marmic/.venv/bin/travelagent-mcp
```

Three things that will bite you if you skip them:

- **Use the absolute path** to `.venv/bin/travelagent-mcp`. Claude Code does not
  inherit your activated venv, so a bare `travelagent-mcp` will not resolve. Get it
  with `echo $(pwd)/.venv/bin/travelagent-mcp`.
- **`-s user`** registers it for every project. Without it the server only exists
  inside this repo, which is not where you plan trips.
- **Pass tokens with `-e`**. The server may start in a different working directory
  and never find your `.env`.

Verify:

```bash
claude mcp list
```

Then in any Claude session, ask it to check `provider_status`. Four tools should be
available: `search_flights`, `search_hotels`, `cheapest_dates`, `provider_status`.

---

## Step 9 — install the skill globally

The MCP tools give Claude the numbers. The skill tells it how to talk about them —
when to say "184 EUR" and when to say "around 96".

```bash
mkdir -p ~/.claude/skills
cp -r .claude/skills/live-travel-prices ~/.claude/skills/
```

Then apply the two edits in `docs/trip-planning-updates.md` to your existing
`trip-planning` skill. They retire its "live fares cannot be retrieved" rule, which
is now false and will otherwise keep it hedging every number the tools return.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `travelagent: command not found` | venv not active | `source .venv/bin/activate` |
| `doctor` shows `403 Forbidden` | Bad or revoked token | Regenerate in the provider dashboard |
| `doctor` shows `egress blocked` | Network is blocking the host | Check VPN, proxy, corporate firewall |
| Searches return one odd airline | Duffel **test** token, working as designed | Request live access |
| `ContractMismatch` | A provider changed its response shape | Send me the message |
| Claude cannot see the tools | Relative path or missing `-s user` | Re-add with the absolute path |
| Scraper: "selector never appeared" | Page layout moved | Check `scraper-debug/` for the HTML and screenshot |

## Running the tests

```bash
pytest          # 97 tests, no network required
ruff check .
```
