"""Exception types that map cleanly onto ProviderError kinds."""

from __future__ import annotations


class TravelAgentError(Exception):
    kind = "error"


class NotConfigured(TravelAgentError):
    """Provider has no credentials. Not a failure, just absent."""

    kind = "unconfigured"


class ProviderTimeout(TravelAgentError):
    kind = "timeout"


class RateLimited(TravelAgentError):
    kind = "rate_limited"


class Blocked(TravelAgentError):
    """Anti-bot wall, captcha, or an egress policy denial."""

    kind = "blocked"


class ContractMismatch(TravelAgentError):
    """The response parsed but did not look like what we expected.

    Raised loudly rather than silently returning zero results, because a
    provider that quietly returns nothing looks identical to a route with no
    flights, and those need very different responses.
    """

    kind = "contract_mismatch"
