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


class TooManyRequestsException(Exception):
    pass
