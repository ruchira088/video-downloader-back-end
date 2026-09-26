import json
from base64 import urlsafe_b64decode, urlsafe_b64encode
from collections.abc import Mapping
from typing import Any

from src.services.exceptions import InvalidPageTokenException


def encode_page_token(key: Mapping[str, Any]) -> str:
    return urlsafe_b64encode(json.dumps(dict(key), sort_keys=True).encode()).decode()


def decode_page_token(token: str) -> dict[str, str]:
    try:
        decoded = json.loads(urlsafe_b64decode(token.encode()))
    except ValueError as error:
        raise InvalidPageTokenException("Malformed page token") from error

    if not isinstance(decoded, dict) or not all(
        isinstance(name, str) and isinstance(value, str)
        for name, value in decoded.items()
    ):
        raise InvalidPageTokenException("Malformed page token")

    # JSON "\ud800" escapes decode to lone surrogates, which are not valid UTF-8 and would
    # fail later, outside this validation, as a 500.
    try:
        for value in decoded.values():
            value.encode()
    except UnicodeEncodeError as error:
        raise InvalidPageTokenException("Malformed page token") from error

    return decoded
