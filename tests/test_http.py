"""Error reporting. A truncated error is worse than a long one."""

from __future__ import annotations

import httpx

from travelagent.http import _provider_message


def _resp(status=403, **kw):
    return httpx.Response(status, request=httpx.Request("POST", "https://api.duffel.com/x"), **kw)


def test_provider_message_survives_a_leading_documentation_url():
    """Duffel's real 403. A blind slice cuts this mid-sentence."""
    body = {
        "errors": [
            {
                "documentation_url": "https://duffel.com/docs/api/overview/response-handling",
                "title": "Insufficient permissions",
                "type": "authentication_error",
                "message": "This endpoint requires a token with 'air.offer_requests.create' permission.",
                "code": "insufficient_permissions",
            }
        ],
        "meta": {"request_id": "GNCZ8ixe-qpTJjEQLNoB", "status": 403},
    }
    out = _provider_message(_resp(json=body))
    assert "air.offer_requests.create" in out
    assert "insufficient_permissions" in out
    assert "documentation_url" not in out


def test_provider_message_reads_a_flat_error_field():
    out = _provider_message(_resp(json={"success": False, "error": "token is invalid"}))
    assert out == "token is invalid"


def test_provider_message_falls_back_to_the_body_when_not_json():
    out = _provider_message(_resp(text="<html>502 Bad Gateway</html>"))
    assert "502 Bad Gateway" in out


def test_provider_message_is_still_bounded():
    out = _provider_message(_resp(json={"message": "x" * 5000}))
    assert len(out) <= 400
