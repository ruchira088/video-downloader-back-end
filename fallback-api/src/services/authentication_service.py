from abc import ABC, abstractmethod

from pydantic import BaseModel, EmailStr

from src.services.access_token_verifier import CognitoAccessTokenVerifier
from src.services.cognito_helpers import secret_hash
from src.services.exceptions import (
    IncorrectCredentialsException,
    InvalidAuthenticationTokenException,
    PasswordResetRequiredException,
    ServiceUnavailableException,
    TooManyRequestsException,
)
from src.services.models.user import User, parse_role


class AuthenticationToken(BaseModel):
    access_token: str
    token_type: str
    expires_in: int
    refresh_token: str
    id_token: str


class AuthenticationService(ABC):
    @abstractmethod
    def login(self, email: EmailStr, password: str) -> AuthenticationToken:
        pass

    @abstractmethod
    def authenticate(self, token: str) -> User:
        pass

    @abstractmethod
    def logout(self, token: str) -> User:
        pass


class CognitoAuthenticationService(AuthenticationService):
    def __init__(
        self,
        cognito_idp_client,
        cognito_user_pool_client_id: str,
        client_secret_key: str,
        access_token_verifier: CognitoAccessTokenVerifier,
    ):
        self._cognito_idp_client = cognito_idp_client
        self._cognito_user_pool_client_id = cognito_user_pool_client_id
        self._client_secret_key = client_secret_key
        self._access_token_verifier = access_token_verifier

    def login(self, email: EmailStr, password: str) -> AuthenticationToken:
        try:
            response = self._cognito_idp_client.initiate_auth(
                ClientId=self._cognito_user_pool_client_id,
                AuthFlow="USER_PASSWORD_AUTH",
                AuthParameters={
                    "USERNAME": email,
                    "PASSWORD": password,
                    "SECRET_HASH": secret_hash(
                        email,
                        self._cognito_user_pool_client_id,
                        self._client_secret_key,
                    ),
                },
            )

            authentication_result = response["AuthenticationResult"]

            access_token = authentication_result["AccessToken"]
            expires_in = int(authentication_result["ExpiresIn"])
            token_type = authentication_result["TokenType"]
            refresh_token = authentication_result["RefreshToken"]
            id_token = authentication_result["IdToken"]

            authentication_token = AuthenticationToken(
                access_token=access_token,
                token_type=token_type,
                expires_in=expires_in,
                refresh_token=refresh_token,
                id_token=id_token,
            )

            return authentication_token
        except (
            self._cognito_idp_client.exceptions.NotAuthorizedException,
            # Same response as a wrong password, so logins can't probe which emails exist.
            self._cognito_idp_client.exceptions.UserNotFoundException,
        ):
            raise IncorrectCredentialsException()
        except self._cognito_idp_client.exceptions.PasswordResetRequiredException:
            raise PasswordResetRequiredException()
        except self._cognito_idp_client.exceptions.TooManyRequestsException:
            raise TooManyRequestsException()

    def authenticate(self, token: str) -> User:
        # GetUser takes no user pool id, so it accepts tokens from any pool: verify first that
        # this pool issued the token for this client.
        claims = self._access_token_verifier.verify(token)

        # Still call GetUser, which rejects revoked tokens (e.g. after a global sign-out).
        response = self._with_access_token(self._cognito_idp_client.get_user, token)

        if response["Username"] != claims["username"]:
            raise InvalidAuthenticationTokenException()

        attributes = {a["Name"]: a["Value"] for a in response["UserAttributes"]}

        return User(
            id=attributes["custom:user_id"],
            email=attributes["email"],
            first_name=attributes["given_name"],
            last_name=attributes["family_name"],
            role=parse_role(attributes.get("custom:role")),
        )

    def logout(self, token: str) -> User:
        user = self.authenticate(token)

        self._with_access_token(self._cognito_idp_client.global_sign_out, token)

        return user

    def _with_access_token(self, call, token: str):
        """Calls a Cognito API that takes an access token, mapping its documented errors like
        login does, so none of them surfaces as a 500."""
        exceptions = self._cognito_idp_client.exceptions

        try:
            return call(AccessToken=token)
        except (
            exceptions.NotAuthorizedException,
            # The user was deleted, or not confirmed, after the token was issued.
            exceptions.UserNotFoundException,
            exceptions.UserNotConfirmedException,
        ):
            raise InvalidAuthenticationTokenException()
        except exceptions.PasswordResetRequiredException:
            raise PasswordResetRequiredException()
        except exceptions.TooManyRequestsException:
            raise TooManyRequestsException()
        except exceptions.InternalErrorException as error:
            raise ServiceUnavailableException("Cognito is unavailable") from error
