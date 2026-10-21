import logging

from pydantic import validate_email
from pydantic_core import PydanticCustomError

from src.sync.messages import UserRevocation

logger = logging.getLogger(__name__)


class CognitoUserRevoker:
    """Deletes the fallback account of a user whose main-side credentials changed or who was
    deleted, which ends their sessions too: their tokens are rejected by GetUser, which
    authenticate() always calls. Their next POST /user, which the web app makes after each
    main-API login, creates the account again with their current password. A sign-up racing
    the revocation is undone by that sign-up's own re-check (CognitoUserService.upsert_user)."""

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

        # Deleted however recently the account was modified: users can update some of their own
        # attributes with their access token, so the modification time can't tell a re-enrolment
        # apart. A revocation that arrives after the user re-enrolled (e.g. redriven from the
        # dead-letter queue) only makes them re-enrol once more.
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
