# Running travelagent on Windows

The complete runbook for Windows. If you are on macOS or Linux, use
[SETUP.md](SETUP.md) instead — the commands differ enough that mixing them will
waste your afternoon.

Everything here runs on **your PC**. Live prices do not work from a Claude Code web
session, because that sandbox blocks outbound traffic to travel APIs.

---

## Step 1 — open PowerShell

Press **Start**, type `powershell`, and open **Windows PowerShell**.

A blue window opens with a prompt like `PS C:\Users\Mario>`. Every command below
gets typed there, one line at a time, pressing Enter after each.

> Use **PowerShell**, not the old `cmd` Command Prompt. Some commands below are
> PowerShell-only.

---

## Step 2 — check what you already have

Run these two:

```powershell
git --version
python --version
```

You want something like `git version 2.43.0` and `Python 3.11.x` or higher.

**If `git` is missing** ("not recognized as the name of a cmdlet"):

```powershell
winget install --id Git.Git -e
```

**If `python` is missing, or it opens the Microsoft Store:**

```powershell
winget install --id Python.Python.3.12 -e
```

> The Store pop-up is a Windows quirk: it ships a fake `python.exe` that only
> advertises the Store. Installing with `winget` replaces it.

**Close PowerShell and open it again** after either install, or the new commands
will not be on your PATH. Then re-run the two version checks before continuing.

---

## Step 3 — pick a folder and download the code

```powershell
cd $env:USERPROFILE
git clone https://github.com/bippityboppity1/marmic.git
cd marmic
git checkout claude/travel-agent-realtime-prices-8ns6a3
```

Line by line:

| Command | What it does |
| --- | --- |
| `cd $env:USERPROFILE` | Move to your user folder, `C:\Users\<you>` |
| `git clone ...` | Download the repo into `C:\Users\<you>\marmic` |
| `cd marmic` | Step into it |
| `git checkout claude/...` | Switch to the branch with this code on it |

**Check it worked:**

```powershell
dir
```

You should see `travelagent`, `tests`, `docs`, `pyproject.toml`, `README.md`.

> Already cloned it before? Skip to `cd $env:USERPROFILE\marmic` and run
> `git pull` instead.

---

## Step 4 — create the virtual environment

A venv keeps this project's packages separate from the rest of your system.

```powershell
python -m venv .venv
.venv\Scripts\Activate.ps1
```

**If you get a red "running scripts is disabled on this system" error** — this is
the most common Windows snag. PowerShell blocks scripts by default. Allow it for
this window only:

```powershell
Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass
.venv\Scripts\Activate.ps1
```

That change lasts until you close the window and is not a permanent security
setting.

**You will know it worked** when your prompt gains a `(.venv)` prefix:

```
(.venv) PS C:\Users\Mario\marmic>
```

You need that prefix every time you use the tool. If you close PowerShell and come
back later, re-run `cd $env:USERPROFILE\marmic` then `.venv\Scripts\Activate.ps1`.

---

## Step 5 — install

```powershell
pip install -e ".[mcp]"
```

Note the quotes — PowerShell needs them around `.[mcp]`.

**Check it worked:**

```powershell
travelagent --help
```

You should see five subcommands: `flights`, `hotels`, `calendar`, `doctor`, `cache`.

Optional, only if you want browser-driven price reading later:

```powershell
pip install -e ".[all]"
playwright install chromium
```

---

## Step 6 — get a Duffel token

Duffel is the only source that returns fares you can quote as prices rather than
ranges.

1. Sign up at **https://duffel.com**
2. Dashboard → **Developers** → **Access tokens** → create one

You will get one of two kinds:

| Token | Returns | Good for |
| --- | --- | --- |
| `duffel_test_...` | **Duffel Airways only — an airline that does not exist** | Proving the setup works |
| `duffel_live_...` | Real fares from 300+ real airlines | Actual trip planning |

**Read that twice before you judge your first search.** A test token returns
invented flights. If results come back with one strange carrier and odd prices,
nothing is broken — that is the sandbox. Request live access in the Duffel
dashboard (short review) and use the test token meanwhile.

**Optional but worth it:** a free token from **https://travelpayouts.com**
(Dashboard → Developers → API tokens). Instant, no review, and it powers the
month-wide price calendar where the biggest savings usually are.

---

## Step 7 — save your tokens

```powershell
Copy-Item .env.example .env
notepad .env
```

Notepad opens. Fill in your token(s) and save:

```
DUFFEL_ACCESS_TOKEN=duffel_test_your_token_here
TRAVELPAYOUTS_TOKEN=your_token_here
TRAVELAGENT_CURRENCY=EUR
TRAVELAGENT_HOME_AIRPORTS=NAP,FCO
```

No quotes around the values, no spaces around the `=`. The file is gitignored and
will not be committed.

---

## Step 8 — verify before trusting anything

```powershell
travelagent doctor
```

**This is the important step.** The provider contracts were written from published
documentation and pinned by tests, but no request has ever been made to a live API.
`doctor` makes the cheapest real call each provider offers.

What success looks like:

```
| duffel        | ✅ ready | authenticated (test mode, airlines endpoint returned 1 row) |
| travelpayouts | ✅ ready | authenticated (LON->PAR probe returned 12 cached fares)      |
| hotellook     | ✅ ready | reachable (with token, Rome probe returned 1 properties)     |
```

If nothing is ready, the footer says which of two problems you have — a missing key
and an unreachable host need different fixes, and it names them separately.

**If you see `ContractMismatch`,** a field name in that adapter is wrong. Send me
the message and it is a one-line fix.

---

## Step 9 — your first searches

```powershell
travelagent calendar NAP LHR --month 2026-10
travelagent flights NAP LHR --depart 2026-10-15 --return 2026-10-22 --flex 3
travelagent hotels Naples --checkin 2026-10-15 --checkout 2026-10-18 --min-stars 3
```

Read the **Basis** column. `bookable` is a real sellable price. `cached` is a fare
someone saw recently that may already be gone. The tool will not blur those two and
neither should you.

Browser-driven reading, only if you installed it in Step 5:

```powershell
$env:TRAVELAGENT_SCRAPING="1"
$env:TRAVELAGENT_SCRAPER_DEBUG_DIR=".\scraper-debug"
travelagent flights NAP LHR --depart 2026-10-15
```

Off by default on purpose. It is throttled, and it sits against most travel sites'
terms of service — fine for reading prices for your own trip, not for volume.

---

## Step 10 — connect it to Claude

This is the point of all of it: real prices inside a planning conversation.

First get the exact path to the server:

```powershell
echo "$PWD\.venv\Scripts\travelagent-mcp.exe"
```

It prints something like
`C:\Users\Mario\marmic\.venv\Scripts\travelagent-mcp.exe`. Copy that.

Then register it, pasting your path and token:

```powershell
claude mcp add travelagent -s user -e DUFFEL_ACCESS_TOKEN=duffel_test_your_token -- "C:\Users\Mario\marmic\.venv\Scripts\travelagent-mcp.exe"
```

Three things that will bite you if you skip them:

- **Use the full path, ending in `.exe`.** On Windows the commands live in
  `.venv\Scripts\`, not `.venv\bin\`. Claude Code does not inherit your activated
  venv, so a bare `travelagent-mcp` will not resolve.
- **`-s user`** registers it for every project. Without it the server exists only
  inside this folder, which is not where you plan trips.
- **Pass tokens with `-e`.** The server may start in a different working directory
  and never find your `.env`.

**Check it worked:**

```powershell
claude mcp list
```

Then in any Claude session, ask it to run `provider_status`. Four tools should be
available: `search_flights`, `search_hotels`, `cheapest_dates`, `provider_status`.

---

## Step 11 — install the skill

The MCP tools give Claude the numbers. The skill tells it how to talk about them —
when to say "184 EUR" and when to say "around 96".

```powershell
New-Item -ItemType Directory -Force -Path "$env:USERPROFILE\.claude\skills"
Copy-Item -Recurse -Force ".claude\skills\live-travel-prices" "$env:USERPROFILE\.claude\skills\"
```

Then apply the two edits in `docs\trip-planning-updates.md` to your existing
`trip-planning` skill. They retire its "live fares cannot be retrieved" rule, which
is now false and will otherwise keep it hedging every number the tools return.

---

## Troubleshooting

| Symptom | Cause | Fix |
| --- | --- | --- |
| `git` / `python` "not recognized" | Not installed, or PATH not refreshed | Install via `winget`, then **reopen PowerShell** |
| `python` opens the Microsoft Store | Windows' placeholder python | `winget install --id Python.Python.3.12 -e` |
| "running scripts is disabled" | PowerShell execution policy | `Set-ExecutionPolicy -Scope Process -ExecutionPolicy Bypass` |
| `travelagent` not recognized | venv not active | `.venv\Scripts\Activate.ps1` — look for the `(.venv)` prefix |
| `pip install -e .[mcp]` errors | PowerShell parsing the brackets | Quote it: `pip install -e ".[mcp]"` |
| `doctor` shows `403 Forbidden` | Bad or revoked token | Regenerate it in the provider dashboard |
| `doctor` shows `egress blocked` | Network blocking the host | Check VPN, proxy, or corporate firewall |
| Searches return one odd airline | Duffel **test** token, working as designed | Request live access |
| `ContractMismatch` | Provider changed its response shape | Send me the message |
| Claude cannot see the tools | Wrong path, or missing `-s user` | Re-add with the full `.exe` path |

## Running the tests

```powershell
pytest
ruff check .
```

97 tests, no network needed.
