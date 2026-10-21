class IncorrectCredentialsException(Exception):
    pass


class InvalidAuthenticationTokenException(Exception):
    pass


class InvalidUrlException(Exception):
    pass


class InvalidPageTokenException(Exception):
    pass


class InvalidPasswordException(Exception):
    pass


class ServiceUnavailableException(Exception):
    pass


class PasswordResetRequiredException(Exception):
    pass


class IncompleteSignUpException(Exception):
    """The user's Cognito account can't log in yet, e.g. a sign-up stopped part-way and left
    it waiting on a challenge. Signing up again (POST /user) repairs it."""


class TooManyRequestsException(Exception):
    pass
