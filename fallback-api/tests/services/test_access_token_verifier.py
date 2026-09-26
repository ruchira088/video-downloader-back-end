import time
import unittest
from typing import Any

import jwt
from cryptography.hazmat.primitives.asymmetric import rsa

from src.services.access_token_verifier import CognitoAccessTokenVerifier
from src.services.exceptions import InvalidAuthenticationTokenException

ISSUER = "https://cognito-idp.ap-southeast-2.amazonaws.com/ap-southeast-2_pool"
CLIENT_ID = "client-1"

_signing_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)
_other_key = rsa.generate_private_key(public_exponent=65537, key_size=2048)


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


def _token(key: Any = _signing_key, algorithm: str = "RS256", **overrides) -> str:
    return jwt.encode(_claims(**overrides), key, algorithm=algorithm)


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

    def test_a_failure_to_resolve_the_signing_key_is_rejected(self):
        def unavailable(token: str) -> Any:
            raise jwt.PyJWKClientConnectionError("JWKS unreachable")

        verifier = CognitoAccessTokenVerifier(
            issuer=ISSUER, client_id=CLIENT_ID, signing_key_resolver=unavailable
        )

        with self.assertRaises(InvalidAuthenticationTokenException):
            verifier.verify(_token())
