"""Where credentials are read from, and what happens when they are not there."""

from __future__ import annotations

from travelagent.config import Config


def _clear(monkeypatch):
    for k in ("DUFFEL_ACCESS_TOKEN", "DUFFEL_TOKEN", "TRAVELAGENT_DOTENV"):
        monkeypatch.delenv(k, raising=False)


def test_reads_dotenv_from_the_working_directory(tmp_path, monkeypatch):
    _clear(monkeypatch)
    (tmp_path / ".env").write_text("DUFFEL_ACCESS_TOKEN=from_cwd\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    assert Config.from_env().duffel_token == "from_cwd"


def test_dotenv_env_var_wins_over_the_working_directory(tmp_path, monkeypatch):
    """The MCP server runs from an arbitrary directory and must still find it."""
    _clear(monkeypatch)
    elsewhere = tmp_path / "repo"
    elsewhere.mkdir()
    (elsewhere / ".env").write_text("DUFFEL_ACCESS_TOKEN=from_pointer\n", encoding="utf-8")

    cwd = tmp_path / "somewhere_else"
    cwd.mkdir()
    (cwd / ".env").write_text("DUFFEL_ACCESS_TOKEN=from_cwd\n", encoding="utf-8")
    monkeypatch.chdir(cwd)
    monkeypatch.setenv("TRAVELAGENT_DOTENV", str(elsewhere / ".env"))

    assert Config.from_env().duffel_token == "from_pointer"


def test_a_real_environment_variable_beats_the_file(tmp_path, monkeypatch):
    _clear(monkeypatch)
    (tmp_path / ".env").write_text("DUFFEL_ACCESS_TOKEN=from_file\n", encoding="utf-8")
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("DUFFEL_ACCESS_TOKEN", "from_environ")
    assert Config.from_env().duffel_token == "from_environ"


def test_missing_dotenv_is_not_an_error(tmp_path, monkeypatch):
    _clear(monkeypatch)
    monkeypatch.chdir(tmp_path)
    monkeypatch.setenv("TRAVELAGENT_DOTENV", str(tmp_path / "nope.env"))
    assert Config.from_env().duffel_token is None


def test_a_rotated_token_reaches_a_long_lived_process(tmp_path, monkeypatch):
    """The MCP server outlives edits to .env and must not serve a stale token."""
    _clear(monkeypatch)
    env = tmp_path / ".env"
    monkeypatch.setenv("TRAVELAGENT_DOTENV", str(env))

    env.write_text("DUFFEL_ACCESS_TOKEN=duffel_live_FIRST", encoding="utf-8")
    assert Config.from_env().duffel_token == "duffel_live_FIRST"

    env.write_text("DUFFEL_ACCESS_TOKEN=duffel_live_SECOND", encoding="utf-8")
    assert Config.from_env().duffel_token == "duffel_live_SECOND"


def test_a_real_env_var_still_wins_across_reloads(tmp_path, monkeypatch):
    """Refreshing our own writes must not start trampling -e overrides."""
    _clear(monkeypatch)
    env = tmp_path / ".env"
    monkeypatch.setenv("TRAVELAGENT_DOTENV", str(env))

    env.write_text("DUFFEL_ACCESS_TOKEN=from_file", encoding="utf-8")
    assert Config.from_env().duffel_token == "from_file"

    monkeypatch.setenv("DUFFEL_ACCESS_TOKEN", "from_environ")
    env.write_text("DUFFEL_ACCESS_TOKEN=changed_in_file", encoding="utf-8")
    assert Config.from_env().duffel_token == "from_environ"


def test_a_key_deleted_from_the_file_stops_being_configured(tmp_path, monkeypatch):
    _clear(monkeypatch)
    env = tmp_path / ".env"
    monkeypatch.setenv("TRAVELAGENT_DOTENV", str(env))

    env.write_text("DUFFEL_ACCESS_TOKEN=temporary", encoding="utf-8")
    assert Config.from_env().duffel_token == "temporary"

    env.write_text("TRAVELAGENT_CURRENCY=EUR", encoding="utf-8")
    assert Config.from_env().duffel_token is None
