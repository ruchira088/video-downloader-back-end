import unittest
from unittest.mock import MagicMock, patch

import requests
from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidPasswordException,
    ServiceUnavailableException,
)
from src.services.models.user import Role, User
from src.services.user_service import CognitoUserService, UserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_admin, sample_password, sample_user
from tests.services.test_service_helpers import (
    moto_access_token_verifier,
    setup_cognito,
)

# Meets moto's default password policy, which needs upper and lower case, a digit and a symbol
NEW_PASSWORD = "New-Passw0rd-9!"


def _attributes(cognito_client, user_pool_id: str, username: str) -> dict[str, str]:
    response = cognito_client.admin_get_user(UserPoolId=user_pool_id, Username=username)
    return {a["Name"]: a["Value"] for a in response["UserAttributes"]}


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)
        self.cognito_client = self.cognito_details.cognito_client

        self.user_validation_service = MagicMock(spec=UserValidationService)

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_id=self.cognito_details.user_pool_id,
        )

    def _attributes(self, email: str) -> dict[str, str]:
        return _attributes(
            self.cognito_client, self.cognito_details.user_pool_id, email
        )

    def _login(self, email: str, password: str) -> None:
        CognitoAuthenticationService(
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_client_id=self.cognito_details.user_pool_client_id,
            client_secret_key=self.cognito_details.user_pool_client_secret,
            access_token_verifier=moto_access_token_verifier(self.cognito_details),
        ).login(email, password)

    def _upsert(self, user: User, password: str = sample_password):
        self.user_validation_service.get_user.return_value = user
        return self.user_service.upsert_user(email=user.email, password=password)

    def test_a_new_user_is_created(self):
        upsert = self._upsert(sample_user)

        self.user_validation_service.get_user.assert_called_with(
            email=sample_user.email, password=sample_password
        )
        self.assertIs(upsert.user, sample_user)
        self.assertTrue(upsert.created)

        attributes = self._attributes(sample_user.email)
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["custom:role"], "User")
        self.assertEqual(attributes["given_name"], sample_user.first_name)
        self._login(sample_user.email, sample_password)

    def test_a_new_admin_is_created_with_the_admin_role(self):
        self._upsert(sample_admin)

        self.assertEqual(self._attributes(sample_admin.email)["custom:role"], "Admin")

    def test_an_existing_user_is_refreshed_from_the_main_api(self):
        self._upsert(sample_user)

        # The main API now has a new name, the Admin role and a new password
        promoted = sample_user.model_copy(
            update={"first_name": "Johnny", "role": Role.ADMIN}
        )
        upsert = self._upsert(promoted, password=NEW_PASSWORD)

        self.assertFalse(upsert.created)
        attributes = self._attributes(sample_user.email)
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["given_name"], "Johnny")
        self.assertEqual(attributes["custom:role"], "Admin")
        self._login(sample_user.email, NEW_PASSWORD)
        with self.assertRaises(IncorrectCredentialsException):
            self._login(sample_user.email, sample_password)

    def test_a_user_signed_up_before_roles_existed_gets_their_role(self):
        self.cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username=sample_admin.email,
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": sample_admin.email},
                {"Name": "custom:user_id", "Value": sample_admin.id},
            ],
        )

        upsert = self._upsert(sample_admin)

        self.assertFalse(upsert.created)
        self.assertEqual(self._attributes(sample_admin.email)["custom:role"], "Admin")

    def test_an_email_now_belonging_to_another_main_side_user_gets_a_new_account(self):
        self._upsert(sample_user)

        # The original user was deleted on the main API and the email reused
        successor = sample_user.model_copy(update={"id": "successor-id"})
        upsert = self._upsert(successor, password=NEW_PASSWORD)

        self.assertTrue(upsert.created)
        self.assertEqual(
            self._attributes(sample_user.email)["custom:user_id"], "successor-id"
        )
        self._login(sample_user.email, NEW_PASSWORD)

    def test_a_user_created_concurrently_is_refreshed_instead(self):
        self._upsert(sample_user)
        not_found = self.cognito_client.exceptions.UserNotFoundException(
            {"Error": {"Code": "UserNotFoundException", "Message": "x"}}, "AdminGetUser"
        )

        # Another request creates the user between this one's lookup and its create
        with patch.object(self.cognito_client, "admin_get_user", side_effect=not_found):
            upsert = self._upsert(sample_user, password=NEW_PASSWORD)

        self.assertFalse(upsert.created)
        self._login(sample_user.email, NEW_PASSWORD)

    def _main_api_rejection(
        self,
        status_code: int = 401,
        body: bytes = b'{"errorMessages": ["Invalid password"]}',
        content_type: str = "application/json",
    ) -> requests.HTTPError:
        # By default the main API's own refusal: its ExceptionHandler answers an
        # AuthenticationException with a 401 and an ErrorResponse body.
        response = requests.Response()
        response.status_code = status_code
        response._content = body
        response.headers["Content-Type"] = content_type
        return requests.HTTPError(f"{status_code} error", response=response)

    def test_a_password_reset_on_the_main_api_during_sign_up_undoes_the_sign_up(self):
        # The sign-up checked the old password just before the main API reset it, and the
        # reset's revocation reached the fallback before the sign-up wrote to Cognito. The
        # re-check after the write, which the main API now refuses, must undo it.
        self.user_validation_service.get_user.side_effect = [
            sample_user,
            self._main_api_rejection(),
        ]

        with self.assertRaises(requests.HTTPError):
            self.user_service.upsert_user(
                email=sample_user.email, password=sample_password
            )

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_a_reset_during_the_refresh_of_an_existing_user_removes_the_account(self):
        self._upsert(sample_user)
        self.user_validation_service.get_user.side_effect = [
            sample_user,
            self._main_api_rejection(),
        ]

        with self.assertRaises(requests.HTTPError):
            self.user_service.upsert_user(
                email=sample_user.email, password=NEW_PASSWORD
            )

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_the_sign_up_is_kept_when_the_main_api_is_unavailable_for_the_re_check(
        self,
    ):
        # Only a refusal proves the password changed; deleting accounts whenever the main API
        # falters would take the fallback away just as it is needed.
        self.user_validation_service.get_user.side_effect = [
            sample_user,
            ServiceUnavailableException("down"),
        ]

        with self.assertRaises(ServiceUnavailableException):
            self.user_service.upsert_user(
                email=sample_user.email, password=sample_password
            )

        self._login(sample_user.email, sample_password)

    def test_the_sign_up_is_kept_when_the_re_check_is_not_a_credential_refusal(self):
        # A 5xx, or any 4xx but 401 and 403, says nothing about the password.
        for status_code in [500, 502, 400, 404, 429]:
            with self.subTest(status_code=status_code):
                self.user_validation_service.get_user.side_effect = [
                    sample_user,
                    self._main_api_rejection(status_code),
                ]

                with self.assertRaises(requests.HTTPError):
                    self.user_service.upsert_user(
                        email=sample_user.email, password=sample_password
                    )

                self._login(sample_user.email, sample_password)

    def test_a_forbidden_re_check_also_removes_the_account(self):
        self.user_validation_service.get_user.side_effect = [
            sample_user,
            self._main_api_rejection(403),
        ]

        with self.assertRaises(requests.HTTPError):
            self.user_service.upsert_user(
                email=sample_user.email, password=sample_password
            )

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_the_sign_up_is_kept_when_a_proxy_refuses_the_re_check(self):
        # nginx, the forward proxy or a WAF in front of the main API can answer 401 or 403
        # themselves; only the main API's own ErrorResponse proves the password changed.
        for status_code, body, content_type in [
            (403, b"<html><h1>403 Forbidden</h1></html>", "text/html"),
            (401, b"<html>401 Authorization Required</html>", "text/html"),
            (403, b"", "text/plain"),
            (403, b'{"message": "Forbidden"}', "application/json"),
            (401, b'{"errorMessages": []}', "application/json"),
            (401, b'{"errorMessages": "Invalid password"}', "application/json"),
            (401, b'["Invalid password"]', "application/json"),
        ]:
            with self.subTest(status_code=status_code, body=body):
                self.user_validation_service.get_user.side_effect = [
                    sample_user,
                    self._main_api_rejection(status_code, body, content_type),
                ]

                with self.assertRaises(requests.HTTPError):
                    self.user_service.upsert_user(
                        email=sample_user.email, password=sample_password
                    )

                self._login(sample_user.email, sample_password)

    def test_an_account_replaced_concurrently_is_not_removed_by_a_failed_re_check(
        self,
    ):
        # Between this sign-up's write and its re-check, a concurrent sign-up replaced the
        # account for the email's new main-side owner, whose account must stay.
        successor = sample_user.model_copy(update={"id": "successor-id"})
        successor_service = CognitoUserService(
            user_validation_service=MagicMock(
                get_user=MagicMock(return_value=successor)
            ),
            cognito_idp_client=self.cognito_client,
            cognito_user_pool_id=self.cognito_details.user_pool_id,
        )

        checks = iter(["first", "re-check"])

        def validate(email: str, password: str) -> User:
            if next(checks) == "first":
                return sample_user
            successor_service.upsert_user(email=email, password=NEW_PASSWORD)
            raise self._main_api_rejection(401)

        self.user_validation_service.get_user.side_effect = validate

        with self.assertRaises(requests.HTTPError):
            self.user_service.upsert_user(
                email=sample_user.email, password=sample_password
            )

        self.assertEqual(
            self._attributes(sample_user.email)["custom:user_id"], "successor-id"
        )
        self._login(sample_user.email, NEW_PASSWORD)

    def test_the_password_is_checked_with_the_main_api_again_after_the_write(self):
        self._upsert(sample_user)

        self.assertEqual(self.user_validation_service.get_user.call_count, 2)

    def test_a_half_created_user_is_repaired_by_the_next_upsert(self):
        # A sign-up that stopped (e.g. the Lambda timed out) between AdminCreateUser and
        # AdminSetUserPassword, so its rollback never ran: the user is left in
        # FORCE_CHANGE_PASSWORD with a password nobody knows.
        self.cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username=sample_user.email,
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": sample_user.email},
                {"Name": "custom:user_id", "Value": sample_user.id},
                {"Name": "custom:role", "Value": "User"},
            ],
        )

        upsert = self._upsert(sample_user)

        self.assertFalse(upsert.created)
        response = self.cognito_client.admin_get_user(
            UserPoolId=self.cognito_details.user_pool_id, Username=sample_user.email
        )
        self.assertEqual(response["UserStatus"], "CONFIRMED")
        self._login(sample_user.email, sample_password)

    def _upsert_with_password_rejected_as(self, exception_name: str) -> None:
        error = getattr(self.cognito_client.exceptions, exception_name)(
            {"Error": {"Code": exception_name, "Message": "x"}}, "AdminSetUserPassword"
        )
        with patch.object(
            self.cognito_client, "admin_set_user_password", side_effect=error
        ):
            self._upsert(sample_user, password=" leading-whitespace")

    def test_a_password_cognito_rejects_as_an_invalid_parameter_is_an_invalid_password(
        self,
    ):
        # Cognito rejects a password with leading or trailing whitespace this way, rather than
        # with an InvalidPasswordException.
        with self.assertRaises(InvalidPasswordException):
            self._upsert_with_password_rejected_as("InvalidParameterException")

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_a_weak_password_rolls_back_a_new_user(self):
        with self.assertRaises(InvalidPasswordException):
            self._upsert(sample_user, password="weak")

        with self.assertRaises(self.cognito_client.exceptions.UserNotFoundException):
            self._attributes(sample_user.email)

    def test_a_weak_password_keeps_an_existing_user_and_their_old_password(self):
        self._upsert(sample_user)

        with self.assertRaises(InvalidPasswordException):
            self._upsert(sample_user, password="weak")

        self._login(sample_user.email, sample_password)

    def test_credentials_the_main_api_rejects_change_nothing(self):
        self._upsert(sample_user)
        self.user_validation_service.get_user.side_effect = (
            IncorrectCredentialsException()
        )

        with self.assertRaises(IncorrectCredentialsException):
            self.user_service.upsert_user(
                email=sample_user.email, password=NEW_PASSWORD
            )

        self._login(sample_user.email, sample_password)


class TestCognitoUserServicePasswordFailure(unittest.TestCase):
    def _user_service(self, cognito_client: MagicMock) -> CognitoUserService:
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        # A new user: the lookup finds no account
        not_found = type("UserNotFoundException", (Exception,), {})
        cognito_client.exceptions.UserNotFoundException = not_found
        cognito_client.admin_get_user.side_effect = not_found()

        return CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_client,
            cognito_user_pool_id="pool-id",
        )

    def test_a_new_user_is_deleted_when_setting_the_password_fails(self):
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError(
            "weak password"
        )

        with self.assertRaises(RuntimeError):
            self._user_service(cognito_client).upsert_user(
                email=sample_user.email, password="weak"
            )

        cognito_client.admin_delete_user.assert_called_once_with(
            UserPoolId="pool-id", Username=sample_user.email
        )

    def test_the_original_error_is_raised_when_the_rollback_delete_also_fails(self):
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError(
            "weak password"
        )
        cognito_client.admin_delete_user.side_effect = RuntimeError("throttled")

        with self.assertRaisesRegex(RuntimeError, "weak password"):
            self._user_service(cognito_client).upsert_user(
                email=sample_user.email, password="weak"
            )
