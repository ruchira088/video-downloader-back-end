from collections.abc import Callable
from typing import Any

import jwt
from jwt import PyJWKClient

from src.services.exceptions import (
    InvalidAuthenticationTokenException,
    ServiceUnavailableException,
)

# Returns the public key that should have signed the given (still unverified) token.
SigningKeyResolver = Callable[[str], Any]

JWKS_CACHE_SECONDS = 3600
# Well under API Gateway's 29 s integration timeout, so an unreachable JWKS endpoint fails the
# request with a 503 instead of hanging it until the gateway gives up.
JWKS_TIMEOUT_SECONDS = 5
# Tolerates Cognito's clock running a little ahead of (or behind) this Lambda's when checking a
# token's iat and exp.
CLOCK_LEEWAY_SECONDS = 30
# A token whose kid isn't in the cached JWKS triggers a refetch, in case the pool rotated its
# keys, but at most once in this long, so a flood of tokens with made-up kids can't make every
# request fetch the JWKS. Within it, an unknown kid is simply rejected.
UNKNOWN_KID_REFETCH_COOLDOWN_SECONDS = 60


def jwks_signing_key_resolver(jwks_url: str) -> SigningKeyResolver:
    """Look keys up by the token's `kid` in the user pool's JWKS, fetched once and cached."""
    jwks_client = PyJWKClient(
        jwks_url,
        cache_keys=True,
        lifespan=JWKS_CACHE_SECONDS,
        timeout=JWKS_TIMEOUT_SECONDS,
        cooldown_duration=UNKNOWN_KID_REFETCH_COOLDOWN_SECONDS,
    )

    return lambda token: jwks_client.get_signing_key_from_jwt(token).key


class CognitoAccessTokenVerifier:
    """Checks that a bearer token is an access token issued by *this* user pool for *this* client.

    Cognito's GetUser accepts an access token from any user pool, since it takes no pool id. So
    without this check, a token from an attacker's own pool, carrying whatever custom:user_id and
    custom:role they like, would be accepted.
    """

    def __init__(
        self, issuer: str, client_id: str, signing_key_resolver: SigningKeyResolver
    ):
        self._issuer = issuer
        self._client_id = client_id
        self._signing_key_resolver = signing_key_resolver

    def verify(self, token: str) -> dict[str, Any]:
        try:
            claims: dict[str, Any] = jwt.decode(
                token,
                self._signing_key_resolver(token),
                algorithms=["RS256"],
                issuer=self._issuer,
                leeway=CLOCK_LEEWAY_SECONDS,
                options={
                    "require": ["exp", "iss", "client_id", "token_use", "username"]
                },
            )
        except jwt.PyJWKClientConnectionError as error:
            # The keys couldn't be fetched, which says nothing about the token.
            raise ServiceUnavailableException(
                "Unable to fetch the user pool's signing keys"
            ) from error
        except jwt.PyJWTError as error:
            raise InvalidAuthenticationTokenException() from error

        if claims["client_id"] != self._client_id or claims["token_use"] != "access":
            raise InvalidAuthenticationTokenException()

        return claims
