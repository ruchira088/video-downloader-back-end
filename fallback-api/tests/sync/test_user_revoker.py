import unittest
from datetime import UTC, datetime, timedelta
from unittest.mock import MagicMock, patch

from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidAuthenticationTokenException,
)
from src.services.user_service import CognitoUserService
from src.sync.messages import UserRevocation
from src.sync.user_revoker import CognitoUserRevoker
from tests.services.test_data_helpers import sample_password, sample_user
from tests.services.test_service_helpers import (
    moto_access_token_verifier,
    setup_cognito,
)


def _revocation(captured_at: datetime, user_id: str = sample_user.id) -> UserRevocation:
    return UserRevocation(
        user_id=user_id, email=sample_user.email, captured_at=captured_at
    )


@mock_aws
class TestCognitoUserRevoker(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)
        self.cognito_client = self.cognito_details.cognito_client
        self.user_pool_id = self.cognito_details.user_pool_id

        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_id=self.user_pool_id,
        ).upsert_user(email=sample_user.email, password=sample_password)

        self.authentication_service = CognitoAuthenticationService(
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_client_id=self.cognito_details.user_pool_client_id,
            client_secret_key=self.cognito_details.user_pool_client_secret,
            access_token_verifier=moto_access_token_verifier(self.cognito_details),
        )
        self.revoker = CognitoUserRevoker(self.cognito_client, self.user_pool_id)

    def _account_exists(self) -> bool:
        try:
            self.cognito_client.admin_get_user(
                UserPoolId=self.user_pool_id, Username=sample_user.email
            )
            return True
        except self.cognito_client.exceptions.UserNotFoundException:
            return False

    def test_a_revoked_user_can_no_longer_log_in_nor_use_their_tokens(self):
        token = self.authentication_service.login(sample_user.email, sample_password)

        # The main API reset the user's password, or deleted them, just now.
        self.revoker.revoke(_revocation(datetime.now(UTC)))

        self.assertFalse(self._account_exists())
        with self.assertRaises(IncorrectCredentialsException):
            self.authentication_service.login(sample_user.email, sample_password)
        with self.assertRaises(InvalidAuthenticationTokenException):
            self.authentication_service.authenticate(token.access_token)

    def test_the_email_is_normalised_like_sign_up_names_the_account(self):
        # POST /user names the account after the EmailStr-normalised email (lower-case
        # domain), while the main API sends the email as it stored it.
        self.revoker.revoke(
            UserRevocation(
                user_id=sample_user.id,
                email=sample_user.email.replace("ruchij.com", "Ruchij.COM"),
                captured_at=datetime.now(UTC),
            )
        )

        self.assertFalse(self._account_exists())

    def test_an_account_its_user_modified_after_the_revocation_is_still_revoked(self):
        # The app client lets users update some of their own attributes with their access
        # token, which bumps UserLastModifiedDate without the main API re-checking anything,
        # so nothing the account's modification time says may spare it.
        token = self.authentication_service.login(sample_user.email, sample_password)
        captured_at = datetime.now(UTC) - timedelta(hours=1)
        self.cognito_client.update_user_attributes(
            AccessToken=token.access_token,
            UserAttributes=[{"Name": "given_name", "Value": "Still-here"}],
        )

        self.revoker.revoke(_revocation(captured_at))

        self.assertFalse(self._account_exists())

    def test_an_account_of_another_main_side_user_with_the_email_is_kept(self):
        # The revoked user was deleted on the main API and the email has since been reused.
        self.revoker.revoke(_revocation(datetime.now(UTC), user_id="deleted-user-id"))

        self.assertTrue(self._account_exists())

    def test_revoking_a_user_without_a_fallback_account_does_nothing(self):
        self.revoker.revoke(
            UserRevocation(
                user_id="someone",
                email="never-signed-up@ruchij.com",
                captured_at=datetime.now(UTC),
            )
        )

        self.assertTrue(self._account_exists())

    def test_an_account_deleted_concurrently_is_already_revoked(self):
        not_found = self.cognito_client.exceptions.UserNotFoundException(
            {"Error": {"Code": "UserNotFoundException", "Message": "x"}},
            "AdminDeleteUser",
        )

        with patch.object(
            self.cognito_client, "admin_delete_user", side_effect=not_found
        ):
            self.revoker.revoke(_revocation(datetime.now(UTC)))
