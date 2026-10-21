import logging
from datetime import timedelta

from pydantic import validate_email
from pydantic_core import PydanticCustomError

from src.sync.messages import UserRevocation

logger = logging.getLogger(__name__)

# How long after a revocation's capturedAt a sign-up may still have checked the old password
# with the main API: a sign-up makes its main-API calls (at most about 26 s, see
# user_validation_service.py) before it writes to Cognito. Generous, to allow for clock skew
# between the main API's host and Cognito; an account last modified later than this was
# re-enrolled with the new password, and is kept.
REENROLMENT_GRACE = timedelta(minutes=15)


class CognitoUserRevoker:
    """Deletes the fallback account of a user whose main-side credentials changed or who was
    deleted, which ends their sessions too: their tokens are rejected by GetUser, which
    authenticate() always calls. Their next POST /user, which the web app makes after each
    main-API login, creates the account again with their current password."""

    def __init__(self, cognito_idp_client, user_pool_id: str):
        self._cognito_idp_client = cognito_idp_client
        self._user_pool_id = user_pool_id

    def revoke(self, revocation: UserRevocation) -> None:
        exceptions = self._cognito_idp_client.exceptions
        username = _username(revocation.email)

        try:
            response = self._cognito_idp_client.admin_get_user(
                UserPoolId=self._user_pool_id, Username=username
            )
        except exceptions.UserNotFoundException:
            logger.info("User %s has no fallback account to revoke", revocation.user_id)
            return

        attributes = {a["Name"]: a["Value"] for a in response["UserAttributes"]}
        if attributes.get("custom:user_id") != revocation.user_id:
            # The email now belongs to another main-side user (the revoked one was deleted),
            # whose account must stay.
            logger.info(
                "Not revoking the fallback account of %s, which belongs to another user",
                revocation.user_id,
            )
            return

        last_modified = response["UserLastModifiedDate"]
        if last_modified > revocation.captured_at + REENROLMENT_GRACE:
            logger.info(
                "Not revoking the fallback account of %s, re-enrolled at %s, after the "
                "revocation captured at %s",
                revocation.user_id,
                last_modified.isoformat(),
                revocation.captured_at.isoformat(),
            )
            return

        try:
            self._cognito_idp_client.admin_delete_user(
                UserPoolId=self._user_pool_id, Username=username
            )
        except exceptions.UserNotFoundException:
            return

        logger.info("Revoked the fallback account of user %s", revocation.user_id)


def _username(email: str) -> str:
    """The username POST /user gives the account: the email as its EmailStr normalises it."""
    try:
        _, normalised = validate_email(email)
        return normalised
    except PydanticCustomError:
        # Not an email sign-up would accept, so no account can be named after it either way.
        return email
