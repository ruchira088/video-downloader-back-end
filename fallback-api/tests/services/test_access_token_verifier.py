import io
import json
import time
import unittest
from typing import Any
from unittest.mock import MagicMock, patch
from urllib.error import URLError

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from src.services.access_token_verifier import (
    CognitoAccessTokenVerifier,
    jwks_signing_key_resolver,
)
from src.services.exceptions import (
    InvalidAuthenticationTokenException,
    ServiceUnavailableException,
)

ISSUER = "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_pool"
CLIENT_ID = "client-1"
JWKS_URL = f"{ISSUER}/.well-known/jwks.json"

_signing_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)


def _public_jwk() -> dict[str, Any]:
    return json.loads(jwt.algorithms.RSAAlgorithm.to_jwk(_signing_key.public_key()))


def _claims(**overrides: Any) -> dict[str, Any]:
    now = int(time.time())
    claims: dict[str, Any] = {
        "iss": ISSUER,
        "sub": "sub-1",
        "client_id": CLIENT_ID,
        "token_use": "access",
        "username": "me@ruchij.com",
        "iat": now,
        "exp": now + 3600,
    }
    claims.update(overrides)
    return {name: value for name, value in claims.items() if value is not None}


def _token(
    key: Any = _signing_key,
    algorithm: str = "RS256",
    headers: dict[str, Any] | None = None,
    **overrides,
) -> str:
    return jwt.encode(_claims(**overrides), key, algorithm=algorithm, headers=headers)


class TestCognitoAccessTokenVerifier(unittest.TestCase):
    def setUp(self):
        self.verifier = CognitoAccessTokenVerifier(
            issuer=ISSUER,
            client_id=CLIENT_ID,
            signing_key_resolver=lambda token: _signing_key.public_key(),
        )

    def _assert_rejected(self, token: str) -> None:
        with self.assertRaises(InvalidAuthenticationTokenException):
            self.verifier.verify(token)

    def test_a_valid_access_token_is_accepted(self):
        claims = self.verifier.verify(_token())

        self.assertEqual(claims["username"], "me@ruchij.com")

    def test_a_token_from_another_issuer_is_rejected(self):
        self._assert_rejected(
            _token(iss="https://cognito-idp.ap-southeast-2.amazonaws.com/attacker")
        )

    def test_a_token_for_another_client_is_rejected(self):
        self._assert_rejected(_token(client_id="attacker-client"))

    def test_an_id_token_is_rejected(self):
        self._assert_rejected(_token(token_use="id"))

    def test_an_id_token_with_an_audience_is_rejected(self):
        self._assert_rejected(_token(token_use="id", client_id=None, aud=CLIENT_ID))

    def test_an_expired_token_is_rejected(self):
        self._assert_rejected(_token(exp=int(time.time()) - 60))

    def test_a_token_issued_slightly_in_the_future_is_accepted(self):
        # Cognito's clock may run a little ahead of this Lambda's.
        claims = self.verifier.verify(_token(iat=int(time.time()) + 20))

        self.assertEqual(claims["username"], "me@ruchij.com")

    def test_a_token_issued_well_in_the_future_is_rejected(self):
        self._assert_rejected(_token(iat=int(time.time()) + 120))

    def test_a_token_expired_well_beyond_the_leeway_is_rejected(self):
        self._assert_rejected(_token(exp=int(time.time()) - 120))

    def test_a_token_without_an_expiry_is_rejected(self):
        self._assert_rejected(_token(exp=None))

    def test_a_token_without_a_username_is_rejected(self):
        self._assert_rejected(_token(username=None))

    def test_a_token_signed_by_another_key_is_rejected(self):
        self._assert_rejected(_token(key=_other_key))

    def test_an_unsigned_token_is_rejected(self):
        self._assert_rejected(_token(key=None, algorithm="none"))

    def test_an_hs256_token_is_rejected(self):
        self._assert_rejected(
            _token(key="a-shared-secret-of-enough-length!", algorithm="HS256")
        )

    def test_a_malformed_token_is_rejected(self):
        self._assert_rejected("invalid-token")

    def test_a_signing_key_that_cant_be_found_is_rejected(self):
        def unknown(token: str) -> Any:
            raise jwt.PyJWKClientError("Unable to find a signing key")

        verifier = CognitoAccessTokenVerifier(
            issuer=ISSUER, client_id=CLIENT_ID, signing_key_resolver=unknown
        )

        with self.assertRaises(InvalidAuthenticationTokenException):
            verifier.verify(_token())

    def test_an_unreachable_jwks_endpoint_is_reported_as_unavailable(self):
        def unavailable(token: str) -> Any:
            raise jwt.PyJWKClientConnectionError("JWKS unreachable")

        verifier = CognitoAccessTokenVerifier(
            issuer=ISSUER, client_id=CLIENT_ID, signing_key_resolver=unavailable
        )

        with self.assertRaises(ServiceUnavailableException):
            verifier.verify(_token())


class TestJwksSigningKeyResolver(unittest.TestCase):
    def test_the_jwks_is_fetched_with_a_short_timeout(self):
        opener = MagicMock()
        opener.open.side_effect = URLError("timed out")

        with patch("urllib.request.build_opener", return_value=opener):
            resolve = jwks_signing_key_resolver(JWKS_URL)
            with self.assertRaises(ServiceUnavailableException):
                resolve(_token(headers={"kid": "key-1"}))

        self.assertEqual(opener.open.call_args.kwargs["timeout"], 5)

    def test_a_jwks_endpoint_returning_a_broken_body_is_reported_as_unavailable(self):
        for body in [b"<html>Bad gateway</html>", b"[]", b'{"keys": []}']:
            opener = MagicMock()
            opener.open.side_effect = lambda *args, body=body, **kwargs: io.BytesIO(
                body
            )

            with (
                self.subTest(body=body),
                patch("urllib.request.build_opener", return_value=opener),
                self.assertRaises(ServiceUnavailableException),
            ):
                jwks_signing_key_resolver(JWKS_URL)(_token(headers={"kid": "key-1"}))

    def test_an_unknown_kid_refetches_the_jwks_at_most_once_a_minute(self):
        jwks = {"keys": [{**_public_jwk(), "kid": "key-1", "use": "sig"}]}
        opener = MagicMock()
        opener.open.side_effect = lambda *args, **kwargs: io.BytesIO(
            json.dumps(jwks).encode()
        )
        clock = {"now": 1000.0}

        with (
            patch("urllib.request.build_opener", return_value=opener),
            patch("jwt.jwks_client.time.monotonic", side_effect=lambda: clock["now"]),
        ):
            resolve = jwks_signing_key_resolver(JWKS_URL)
            resolve(_token(headers={"kid": "key-1"}))
            self.assertEqual(opener.open.call_count, 1)

            # Within the cooldown, unknown kids are rejected without fetching.
            clock["now"] += 59
            for kid in ["unknown-1", "unknown-2", "unknown-3"]:
                with self.assertRaises(jwt.PyJWKClientError):
                    resolve(_token(headers={"kid": kid}))
            self.assertEqual(opener.open.call_count, 1)

            # After it, one unknown kid refetches, and starts the cooldown again.
            clock["now"] += 2
            for kid in ["unknown-4", "unknown-5"]:
                with self.assertRaises(jwt.PyJWKClientError):
                    resolve(_token(headers={"kid": kid}))
            self.assertEqual(opener.open.call_count, 2)
