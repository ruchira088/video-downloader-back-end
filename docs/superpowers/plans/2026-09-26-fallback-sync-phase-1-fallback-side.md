# Fallback Sync, Phase 1 (Fallback Side) Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make `fallback-api/` hold a DynamoDB copy of scheduled videos, accept new schedule requests onto an SQS
queue, apply sync messages from the main side, and serve role-aware listings — deployable before the main side sends
anything.

**Architecture:** FastAPI on Lambda (existing `ApiFunction`) gains `POST/GET /schedule` backed by a DynamoDB
single-table design and an SQS producer. A new `SyncFunction` Lambda consumes `main-to-fallback` messages and applies
them with guarded DynamoDB transactions. Cognito users gain a `custom:role` attribute; admins list all videos through
a sparse GSI.

**Tech Stack:** Python 3.14, FastAPI, pydantic 2, boto3 (DynamoDB resource, SQS, Cognito IDP), moto for tests,
pytest, ruff, mypy, AWS SAM, cfn-lint.

**Spec:** `docs/superpowers/specs/2026-09-26-fallback-sync-design.md`

## Global Constraints

- All commands run from `fallback-api/` with the project venv: `.venv/bin/pytest`, `.venv/bin/ruff`, `.venv/bin/mypy`.
- Every task ends with `ruff check .`, `ruff format --check .`, `mypy .` and `pytest -q` all passing.
- Pin every new dependency exactly (`==`) in `requirements.txt` / `requirements.dev.txt`, matching existing style.
- Markdown files keep lines at 120 characters or fewer (repo `CLAUDE.md`).
- Sync message JSON uses camelCase keys and a `type` discriminator; timestamps are ISO-8601 UTC with millisecond
  precision and a `Z` suffix (e.g. `2026-09-26T08:15:30.123Z`).
- DynamoDB keys: video `PK=VIDEO#<videoId>`, `SK=VIDEO`; user link `PK=USER#<userId>`,
  `SK=VIDEO#<scheduledAt>#<videoId>`; pending `PK=USER#<userId>`, `SK=PENDING#<requestId>`; sparse GSI `GSI1` with
  `GSI1PK="VIDEO"`, `GSI1SK=<scheduledAt>#<videoId>` on live video items only.
- Rejected pending items get `ttl` = rejection time + 7 days; tombstones get `ttl` = removal time + 1 day.
- `GET /schedule` page size is 25.
- Commit messages follow the repo style (sentence, trailing period) and end with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Deviations from the spec (decided while planning)

- **Sign-up uses `admin_create_user` + `admin_set_user_password(Permanent=True)`** instead of adding `SecretHash`
  to `sign_up`. Admin APIs are not limited by the app client's `WriteAttributes`, so the client can deny writes to
  `custom:role` and `custom:user_id` while sign-up still sets them. The old `sign_up` + `admin_confirm_sign_up`
  path is removed.
- **The client secret is read at startup with `describe_user_pool_client`**, not passed as an environment variable,
  so it never appears in the Lambda configuration.
- **Video items store `userIds` as a DynamoDB list**, not a string set: string sets cannot be empty, and an upsert
  with no users is valid.
- **Lambda log groups use custom names** (`/fallback-api/<stage>/api`, `/fallback-api/<stage>/sync`) wired through
  `LoggingConfig`, because the default `/aws/lambda/<function>` group already exists for the deployed `ApiFunction`
  and CloudFormation cannot create it again.
- **The main-side IAM user has no explicit `UserName`**, so the stack keeps deploying with `CAPABILITY_IAM` (a named
  IAM resource would require `CAPABILITY_NAMED_IAM`).
- **The alarm e-mail is optional** (`AlarmEmail` parameter defaulting to empty); the SNS subscription is only
  created when it is set.
- **The Cognito app client gains `ExplicitAuthFlows: [ALLOW_USER_PASSWORD_AUTH, ALLOW_REFRESH_TOKEN_AUTH]`**:
  `CognitoAuthenticationService.login` uses `USER_PASSWORD_AUTH`, which the current client does not allow.
- **The Lambda gets the Cognito IAM permissions it needs** (`AdminCreateUser`, `AdminSetUserPassword`,
  `AdminDeleteUser`, `DescribeUserPoolClient`); the current template grants none, so sign-up fails in AWS today.

## Review Focus

1. **A duplicate or late `RequestResolved{Scheduled}`** whose embedded upsert loses the `capturedAt` guard must still
   delete the pending item — otherwise the user sees a "Pending" request forever. Test in Task 4.
2. **A tampered `pageToken`** (garbage, or a key from another user's partition) must return 400, never 500 and never
   another user's data. Tests in Tasks 6 and 7.
3. **Users created before `custom:role` existed**, or a main API response without `role`, or an unknown role value,
   must be treated as `User` (least privilege), not crash or grant admin. Tests in Task 1.
4. **An unknown or malformed message on `main-to-fallback`** must fail only its own record (partial batch failure)
   so the rest of the batch is applied. Test in Task 5.
5. **A brand-new user with nothing scheduled** gets `{"videos": [], "pending": [], "nextPageToken": null}` with 200.
   Test in Task 6.

---

## File Structure

| File | Responsibility |
|---|---|
| `src/services/models/user.py` | `User` gains `role: Role` (`User` / `Admin`) |
| `src/services/cognito_helpers.py` (new) | Cognito client factory, client-secret lookup, `SECRET_HASH` computation |
| `src/services/user_service.py` | Sign-up via admin APIs, writes `custom:user_id` and `custom:role` |
| `src/services/user_validation_service.py` | Reads `role` from the main API's logout response |
| `src/services/authentication_service.py` | Reads `custom:role`; stub `get_authentication_service` removed |
| `src/web/depends/authentication.py` | `bearer_token` and `authenticated_user_dependency(...)` factory |
| `src/web/routers/authentication_router.py` | `POST /authentication/login`, `DELETE /authentication/logout` |
| `src/sync/timestamps.py` (new) | `iso_millis` — the one timestamp format used in messages and DynamoDB |
| `src/sync/messages.py` (new) | Pydantic models + parser for the five sync message types |
| `contract/*.json` (new) | One example per message type, shared with the Scala tests in phase 2 |
| `src/sync/items.py` (new) | DynamoDB key builders and item mappers |
| `src/sync/sync_applier.py` (new) | Applies main → fallback messages with guarded transactions |
| `src/sync/sqs_batch.py` (new) | Turns an SQS event into per-record applies + partial batch response |
| `sync_handler.py` (new, root) | `SyncFunction` Lambda entry point |
| `src/config/sync_configuration.py` (new) | Table name and fallback → main queue URL |
| `src/sync/page_tokens.py` (new) | Encode/decode `LastEvaluatedKey` page tokens |
| `src/services/models/scheduled_video.py` (new) | `VideoSummary`, `PendingRequest`, `ScheduleListing` response models |
| `src/services/scheduling_service.py` | `SchedulingService` + `DynamoDbSchedulingService` (schedule + list) |
| `src/web/routers/schedule_router.py` | `POST /schedule` (202) and `GET /schedule` |
| `src/web/handlers/exception_handlers.py` | 400 / 503 mappings for the new exceptions |
| `src/main.py` | Wires Cognito, auth, scheduling |
| `template.yaml`, `application.conf` | Infrastructure and configuration |

---

### Task 1: Cognito role and admin-API sign-up

**Files:**
- Modify: `src/services/models/user.py`
- Create: `src/services/cognito_helpers.py`
- Modify: `src/services/user_service.py`
- Modify: `src/services/user_validation_service.py`
- Modify: `src/services/authentication_service.py`
- Modify: `tests/services/test_service_helpers.py`, `tests/services/test_data_helpers.py`
- Modify: `tests/services/test_user_service.py`, `tests/services/test_authentication_service.py`
- Create: `tests/services/test_user_validation_service.py`, `tests/services/test_cognito_helpers.py`

**Interfaces:**
- Produces:
  - `class Role(StrEnum)` with `USER = "User"`, `ADMIN = "Admin"` in `src/services/models/user.py`
  - `User(id: str, email: EmailStr, first_name: str, last_name: str, role: Role = Role.USER)`
  - `create_cognito_client(cognito_configuration: AwsCognitoConfiguration)` → boto3 `cognito-idp` client
  - `get_client_secret(cognito_idp_client, user_pool_id: str, client_id: str) -> str`
  - `secret_hash(username: str, client_id: str, client_secret: str) -> str`
  - `CognitoUserService(user_validation_service, cognito_idp_client, cognito_user_pool_id: str)`
  - `get_user_service(app_configuration: AppConfiguration, cognito_idp_client) -> UserService`
  - `CognitoAuthenticationService(cognito_idp_client, cognito_user_pool_client_id: str, client_secret_key: str)`
    (unchanged signature; `authenticate` now fills `role`)

- [ ] **Step 1: Update test helpers**

In `tests/services/test_service_helpers.py`, add the `role` attribute to the user pool schema in `setup_cognito`:

```python
    user_pool_creation_response = cognito_client.create_user_pool(
        PoolName=user_pool_name,
        Schema=[
            {
                "Name": "user_id",
                "AttributeDataType": "String",
                "Mutable": False,
            },
            {
                "Name": "role",
                "AttributeDataType": "String",
                "Mutable": True,
            },
        ],
    )
```

In `tests/services/test_data_helpers.py`, add an admin next to `sample_user`:

```python
from src.services.models.user import Role, User

sample_user: User = User(
    id="my-user-id",
    email="me@ruchij.com",
    first_name="John",
    last_name="Doe",
)

sample_admin: User = User(
    id="my-admin-id",
    email="admin@ruchij.com",
    first_name="Jane",
    last_name="Doe",
    role=Role.ADMIN,
)

sample_password: str = "cam2QGH8eht!vbz1nrh"
```

- [ ] **Step 2: Write the failing tests**

Replace `tests/services/test_user_service.py` with:

```python
import unittest
from unittest.mock import MagicMock

from moto import mock_aws

from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_service import CognitoUserService, UserService
from src.services.user_validation_service import UserValidationService
from tests.services.test_data_helpers import sample_admin, sample_password, sample_user
from tests.services.test_service_helpers import setup_cognito


def _attributes(cognito_client, user_pool_id: str, username: str) -> dict[str, str]:
    response = cognito_client.admin_get_user(UserPoolId=user_pool_id, Username=username)
    return {a["Name"]: a["Value"] for a in response["UserAttributes"]}


@mock_aws
class TestCognitoUserService(unittest.TestCase):
    def setUp(self):
        self.cognito_details = setup_cognito(__name__)

        self.user_validation_service: UserValidationService | MagicMock = MagicMock()

        self.user_service: UserService = CognitoUserService(
            user_validation_service=self.user_validation_service,
            cognito_idp_client=self.cognito_details.cognito_client,
            cognito_user_pool_id=self.cognito_details.user_pool_id,
        )

    def test_create_user(self):
        self.user_validation_service.get_user.return_value = sample_user

        created_user: User = self.user_service.create_user(
            email=sample_user.email, password=sample_password
        )

        self.user_validation_service.get_user.assert_called_with(
            email=sample_user.email, password=sample_password
        )
        self.assertIs(sample_user, created_user)

        attributes = _attributes(
            self.cognito_details.cognito_client,
            self.cognito_details.user_pool_id,
            sample_user.email,
        )
        self.assertEqual(attributes["custom:user_id"], sample_user.id)
        self.assertEqual(attributes["custom:role"], "User")
        self.assertEqual(attributes["given_name"], sample_user.first_name)

    def test_create_admin_user_stores_admin_role(self):
        self.user_validation_service.get_user.return_value = sample_admin

        self.user_service.create_user(email=sample_admin.email, password=sample_password)

        attributes = _attributes(
            self.cognito_details.cognito_client,
            self.cognito_details.user_pool_id,
            sample_admin.email,
        )
        self.assertEqual(attributes["custom:role"], "Admin")

    def test_creating_duplicate_user_throws_resource_conflict_exception(self):
        self.user_validation_service.get_user.return_value = sample_user

        self.user_service.create_user(email=sample_user.email, password=sample_password)

        with self.assertRaises(ResourceConflictException):
            self.user_service.create_user(
                email=sample_user.email, password=sample_password
            )


class TestCognitoUserServicePasswordFailure(unittest.TestCase):
    def test_user_is_deleted_when_setting_the_password_fails(self):
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        cognito_client = MagicMock()
        cognito_client.admin_set_user_password.side_effect = RuntimeError("weak password")

        user_service = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_client,
            cognito_user_pool_id="pool-id",
        )

        with self.assertRaises(RuntimeError):
            user_service.create_user(email=sample_user.email, password="weak")

        cognito_client.admin_delete_user.assert_called_once_with(
            UserPoolId="pool-id", Username=sample_user.email
        )
```

In `tests/services/test_authentication_service.py`:

1. Change the `CognitoUserService(...)` construction in `setUp` to the new signature (drop
   `cognito_user_pool_client_id`) and keep `self.cognito_details = cognito_details`:

```python
        self.cognito_details = setup_cognito(__name__)
        cognito_details = self.cognito_details
        user_validation_service: UserValidationService | MagicMock = MagicMock()
        user_validation_service.get_user.return_value = sample_user

        user_service: UserService = CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        )
```

2. Add `from src.services.models.user import Role` and append these tests to the class:

```python
    def test_authenticate_returns_the_user_role(self):
        auth_token: AuthenticationToken = self.cognito_authentication_service.login(
            sample_user.email, sample_password
        )

        user: User = self.cognito_authentication_service.authenticate(
            auth_token.access_token
        )

        assert user.role == Role.USER

    def test_authenticate_defaults_to_user_role_when_the_attribute_is_missing(self):
        cognito_client = self.cognito_details.cognito_client
        cognito_client.admin_create_user(
            UserPoolId=self.cognito_details.user_pool_id,
            Username="legacy@ruchij.com",
            MessageAction="SUPPRESS",
            UserAttributes=[
                {"Name": "email", "Value": "legacy@ruchij.com"},
                {"Name": "given_name", "Value": "Legacy"},
                {"Name": "family_name", "Value": "User"},
                {"Name": "custom:user_id", "Value": "legacy-id"},
            ],
        )
        cognito_client.admin_set_user_password(
            UserPoolId=self.cognito_details.user_pool_id,
            Username="legacy@ruchij.com",
            Password=sample_password,
            Permanent=True,
        )

        auth_token = self.cognito_authentication_service.login(
            "legacy@ruchij.com", sample_password
        )
        user = self.cognito_authentication_service.authenticate(auth_token.access_token)

        assert user.id == "legacy-id"
        assert user.role == Role.USER
```

Create `tests/services/test_user_validation_service.py`:

```python
import unittest
from unittest.mock import MagicMock, patch

from src.services.models.user import Role
from src.services.user_validation_service import VideoDownloaderUserValidationService


def _logout_response(body: dict) -> MagicMock:
    response = MagicMock()
    response.json.return_value = body
    return response


class TestVideoDownloaderUserValidationService(unittest.TestCase):
    def _get_user(self, logout_body: dict):
        login_response = MagicMock()
        login_response.json.return_value = {"secret": "token"}

        with patch("src.services.user_validation_service.requests") as requests:
            requests.post.return_value = login_response
            requests.delete.return_value = _logout_response(logout_body)
            service = VideoDownloaderUserValidationService("https://api.example.com")
            return service.get_user(email="me@ruchij.com", password="secret")

    def _body(self, **overrides) -> dict:
        body = {
            "id": "user-1",
            "email": "me@ruchij.com",
            "firstName": "John",
            "lastName": "Doe",
        }
        body.update(overrides)
        return body

    def test_admin_role_is_read_from_the_logout_response(self):
        user = self._get_user(self._body(role="Admin"))

        self.assertEqual(user.role, Role.ADMIN)

    def test_missing_role_defaults_to_user(self):
        user = self._get_user(self._body())

        self.assertEqual(user.role, Role.USER)

    def test_unknown_role_defaults_to_user(self):
        user = self._get_user(self._body(role="SuperAdmin"))

        self.assertEqual(user.role, Role.USER)
```

Create `tests/services/test_cognito_helpers.py`:

```python
import unittest

from moto import mock_aws

from src.services.cognito_helpers import get_client_secret
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestCognitoHelpers(unittest.TestCase):
    def test_get_client_secret_returns_the_app_client_secret(self):
        cognito_details = setup_cognito(__name__)

        client_secret = get_client_secret(
            cognito_details.cognito_client,
            cognito_details.user_pool_id,
            cognito_details.user_pool_client_id,
        )

        self.assertEqual(client_secret, cognito_details.user_pool_client_secret)
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/services -q`
Expected: FAIL — `ImportError` for `Role` / `sample_admin` / `cognito_helpers`, and `TypeError` for the removed
`cognito_user_pool_client_id` argument.

- [ ] **Step 4: Implement**

Replace `src/services/models/user.py`:

```python
from enum import StrEnum

from pydantic import BaseModel, EmailStr


class Role(StrEnum):
    USER = "User"
    ADMIN = "Admin"


class User(BaseModel):
    id: str
    email: EmailStr
    first_name: str
    last_name: str
    role: Role = Role.USER


def parse_role(value: object) -> Role:
    """Anything other than an exact "Admin" is treated as a regular user."""
    return Role.ADMIN if value == Role.ADMIN.value else Role.USER
```

Create `src/services/cognito_helpers.py`:

```python
import hmac
from base64 import b64encode
from collections.abc import Mapping
from hashlib import sha256
from typing import Any

import boto3

from src.config.aws_cognito_configuration import AwsCognitoConfiguration


def create_cognito_client(cognito_configuration: AwsCognitoConfiguration):
    client_args: Mapping[str, Any] = (
        {}
        if cognito_configuration.endpoint_url is None
        else {"endpoint_url": str(cognito_configuration.endpoint_url)}
    )

    return boto3.client("cognito-idp", **client_args)


def get_client_secret(cognito_idp_client, user_pool_id: str, client_id: str) -> str:
    response = cognito_idp_client.describe_user_pool_client(
        UserPoolId=user_pool_id, ClientId=client_id
    )

    return response["UserPoolClient"]["ClientSecret"]


def secret_hash(username: str, client_id: str, client_secret: str) -> str:
    message = bytes(username + client_id, encoding="utf-8")
    key = bytes(client_secret, encoding="utf-8")

    return b64encode(hmac.new(key, message, digestmod=sha256).digest()).decode()
```

Replace `src/services/user_service.py`:

```python
from abc import ABC, abstractmethod

from pydantic import EmailStr

from src.config.configuration import AppConfiguration
from src.services.exceptions import ResourceConflictException
from src.services.models.user import User
from src.services.user_validation_service import (
    UserValidationService,
    VideoDownloaderUserValidationService,
)


class UserService(ABC):
    @abstractmethod
    def create_user(self, email: EmailStr, password: str) -> User:
        pass


class CognitoUserService(UserService):
    def __init__(
        self,
        user_validation_service: UserValidationService,
        cognito_idp_client,
        cognito_user_pool_id: str,
    ):
        self._user_validation_service = user_validation_service
        self._cognito_idp_client = cognito_idp_client
        self._cognito_user_pool_id = cognito_user_pool_id

    def create_user(self, email: EmailStr, password: str) -> User:
        user = self._user_validation_service.get_user(email=email, password=password)

        # Admin APIs are not limited by the app client's WriteAttributes, so the client can
        # deny users write access to custom:user_id and custom:role.
        try:
            self._cognito_idp_client.admin_create_user(
                UserPoolId=self._cognito_user_pool_id,
                Username=email,
                MessageAction="SUPPRESS",
                UserAttributes=[
                    {"Name": "email", "Value": email},
                    {"Name": "email_verified", "Value": "true"},
                    {"Name": "given_name", "Value": user.first_name},
                    {"Name": "family_name", "Value": user.last_name},
                    {"Name": "custom:user_id", "Value": user.id},
                    {"Name": "custom:role", "Value": user.role.value},
                ],
            )
        except self._cognito_idp_client.exceptions.UsernameExistsException:
            raise ResourceConflictException(f'User with email "{email}" already exists')

        try:
            self._cognito_idp_client.admin_set_user_password(
                UserPoolId=self._cognito_user_pool_id,
                Username=email,
                Password=password,
                Permanent=True,
            )
        except Exception:
            # Without a password the user could never log in, and a retry would hit
            # UsernameExistsException, so undo the creation.
            self._cognito_idp_client.admin_delete_user(
                UserPoolId=self._cognito_user_pool_id, Username=email
            )
            raise

        return user


def get_user_service(app_configuration: AppConfiguration, cognito_idp_client) -> UserService:
    user_validation_service = VideoDownloaderUserValidationService(
        app_configuration.video_downloader.url
    )

    return CognitoUserService(
        user_validation_service,
        cognito_idp_client,
        cognito_user_pool_id=app_configuration.cognito.user_pool_id,
    )
```

In `src/services/user_validation_service.py`, change the import to
`from src.services.models.user import User, parse_role` and make `_logout` return the role:

```python
        return User(
            id=user_id,
            email=email,
            first_name=first_name,
            last_name=last_name,
            role=parse_role(response_body.get("role")),
        )
```

In `src/services/authentication_service.py`:

1. Remove the `hmac`, `b64encode` and `sha256` imports and the `_secret_hash` method. Leave the stub
   `get_authentication_service` at the bottom of the file for now — `src/web/depends/authentication.py` still imports
   it, and Task 2 removes both.
2. Add imports `from src.services.cognito_helpers import secret_hash` and
   `from src.services.models.user import User, parse_role` (replacing the existing `User` import).
3. In `login`, replace `"SECRET_HASH": self._secret_hash(email),` with
   `"SECRET_HASH": secret_hash(email, self._cognito_user_pool_client_id, self._client_secret_key),`.
4. Replace the body of `authenticate` with:

```python
    def authenticate(self, token: str) -> User:
        try:
            response = self._cognito_idp_client.get_user(AccessToken=token)
        except self._cognito_idp_client.exceptions.NotAuthorizedException:
            raise InvalidAuthenticationTokenException()

        attributes = {a["Name"]: a["Value"] for a in response["UserAttributes"]}

        return User(
            id=attributes["custom:user_id"],
            email=attributes["email"],
            first_name=attributes["given_name"],
            last_name=attributes["family_name"],
            role=parse_role(attributes.get("custom:role")),
        )
```

Update the call in `src/main.py` so the app still starts (full rewiring happens in Task 2) — replace the
`user_service: UserService = get_user_service(app_configuration)` line with:

```python
    user_service: UserService = get_user_service(
        app_configuration, create_cognito_client(app_configuration.cognito)
    )
```

and add `from src.services.cognito_helpers import create_cognito_client`.

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.venv/bin/pytest tests/services -q`
Expected: all pass.

- [ ] **Step 6: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src tests
git commit -m "Store the user's role in Cognito and sign fallback users up through the admin APIs.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Authentication dependency and router

**Files:**
- Modify: `src/web/depends/authentication.py`
- Modify: `src/web/routers/authentication_router.py` (currently empty)
- Modify: `src/web/routers/schedule_router.py`
- Modify: `src/main.py`
- Create: `tests/web/__init__.py`, `tests/web/test_authentication_router.py`, `tests/test_main.py`

**Interfaces:**
- Consumes: `CognitoAuthenticationService`, `create_cognito_client`, `get_client_secret` (Task 1).
- Produces:
  - `bearer_token(authorization: str = Header(...)) -> str`
  - `authenticated_user_dependency(authentication_service: AuthenticationService) -> Callable[..., User]`
  - `authentication_router(authentication_service: AuthenticationService) -> APIRouter`
  - `schedule_router(authenticated_user: Callable[..., User]) -> APIRouter` (Task 7 adds the service argument)

- [ ] **Step 1: Write the failing tests**

Create empty `tests/web/__init__.py`, then `tests/web/test_authentication_router.py`:

```python
import unittest
from unittest.mock import MagicMock

from fastapi import Depends, FastAPI
from fastapi.testclient import TestClient
from moto import mock_aws

from src.services.authentication_service import CognitoAuthenticationService
from src.services.models.user import User
from src.services.user_service import CognitoUserService
from src.web.depends.authentication import authenticated_user_dependency
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.authentication_router import authentication_router
from tests.services.test_data_helpers import sample_password, sample_user
from tests.services.test_service_helpers import setup_cognito


@mock_aws
class TestAuthenticationRouter(unittest.TestCase):
    def setUp(self):
        cognito_details = setup_cognito(__name__)
        user_validation_service = MagicMock()
        user_validation_service.get_user.return_value = sample_user
        CognitoUserService(
            user_validation_service=user_validation_service,
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_id=cognito_details.user_pool_id,
        ).create_user(email=sample_user.email, password=sample_password)

        authentication_service = CognitoAuthenticationService(
            cognito_idp_client=cognito_details.cognito_client,
            cognito_user_pool_client_id=cognito_details.user_pool_client_id,
            client_secret_key=cognito_details.user_pool_client_secret,
        )
        authenticated_user = authenticated_user_dependency(authentication_service)

        app = FastAPI()
        app.include_router(authentication_router(authentication_service))

        @app.get("/whoami")
        def whoami(user: User = Depends(authenticated_user)):
            return {"id": user.id, "role": user.role}

        register_exception_handlers(app)
        self.client = TestClient(app)

    def _login(self) -> str:
        response = self.client.post(
            "/authentication/login",
            json={"email": sample_user.email, "password": sample_password},
        )
        self.assertEqual(response.status_code, 200)
        return response.json()["access_token"]

    def test_login_and_use_the_access_token(self):
        token = self._login()

        response = self.client.get("/whoami", headers={"Authorization": f"Bearer {token}"})

        self.assertEqual(response.status_code, 200)
        self.assertEqual(response.json(), {"id": sample_user.id, "role": "User"})

    def test_login_with_wrong_password_returns_401(self):
        response = self.client.post(
            "/authentication/login",
            json={"email": sample_user.email, "password": "wrong-password"},
        )

        self.assertEqual(response.status_code, 401)

    def test_non_bearer_authorization_header_returns_401(self):
        response = self.client.get("/whoami", headers={"Authorization": "Basic abc"})

        self.assertEqual(response.status_code, 401)

    def test_logout_invalidates_the_token(self):
        token = self._login()
        headers = {"Authorization": f"Bearer {token}"}

        logout_response = self.client.delete("/authentication/logout", headers=headers)

        self.assertEqual(logout_response.status_code, 200)
        self.assertEqual(logout_response.json()["id"], sample_user.id)
        self.assertEqual(self.client.get("/whoami", headers=headers).status_code, 401)
```

Create `tests/test_main.py`:

```python
import unittest

from moto import mock_aws

from src.config.aws_cognito_configuration import AwsCognitoConfiguration
from src.config.configuration import AppConfiguration
from src.config.http_configuration import HttpConfiguration
from src.config.video_downloader_configuration import VideoDownloaderConfiguration
from src.main import create_http_app
from tests.services.test_service_helpers import setup_cognito


def app_configuration_for_tests(user_pool_id: str, client_id: str) -> AppConfiguration:
    return AppConfiguration(
        cognito=AwsCognitoConfiguration(user_pool_id=user_pool_id, client_id=client_id),
        http=HttpConfiguration(host="0.0.0.0", port=8000, debug=False),
        video_downloader=VideoDownloaderConfiguration(url="https://api.example.com"),
    )


@mock_aws
class TestCreateHttpApp(unittest.TestCase):
    def test_app_exposes_the_expected_routes(self):
        cognito_details = setup_cognito(__name__)

        app = create_http_app(
            app_configuration_for_tests(
                cognito_details.user_pool_id, cognito_details.user_pool_client_id
            )
        )

        paths = {route.path for route in app.routes}
        self.assertTrue(
            {"/user", "/authentication/login", "/authentication/logout", "/service/info"}
            <= paths
        )
```

`create_http_app` uses `boto3.client("cognito-idp")` with no region, so add a `tests/conftest.py` that gives every
test a region:

```python
import pytest


@pytest.fixture(autouse=True)
def aws_region(monkeypatch):
    monkeypatch.setenv("AWS_DEFAULT_REGION", "ap-southeast-2")
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/web tests/test_main.py -q`
Expected: FAIL — `ImportError: cannot import name 'authenticated_user_dependency'` and `authentication_router`.

- [ ] **Step 3: Implement**

Delete the stub `get_authentication_service` function at the bottom of `src/services/authentication_service.py`,
then replace `src/web/depends/authentication.py`:

```python
from collections.abc import Callable

from fastapi import Depends, Header, HTTPException

from src.services.authentication_service import AuthenticationService
from src.services.models.user import User


def bearer_token(authorization: str = Header(...)) -> str:
    scheme, _, token = authorization.partition(" ")

    if scheme.lower() != "bearer" or not token:
        raise HTTPException(
            status_code=401, detail="Invalid Authorization header format"
        )

    return token


def authenticated_user_dependency(
    authentication_service: AuthenticationService,
) -> Callable[..., User]:
    def get_authenticated_user(token: str = Depends(bearer_token)) -> User:
        return authentication_service.authenticate(token)

    return get_authenticated_user
```

Write `src/web/routers/authentication_router.py`:

```python
from fastapi import APIRouter, Depends
from pydantic import BaseModel, EmailStr

from src.services.authentication_service import (
    AuthenticationService,
    AuthenticationToken,
)
from src.web.depends.authentication import bearer_token
from src.web.routers.user_router import UserResponse


class LoginRequest(BaseModel):
    email: EmailStr
    password: str


def authentication_router(authentication_service: AuthenticationService) -> APIRouter:
    router = APIRouter(prefix="/authentication")

    @router.post("/login", response_model=AuthenticationToken)
    def login(login_request: LoginRequest):
        return authentication_service.login(login_request.email, login_request.password)

    @router.delete("/logout", response_model=UserResponse)
    def logout(token: str = Depends(bearer_token)):
        return UserResponse.from_user(authentication_service.logout(token))

    return router
```

Replace `src/web/routers/schedule_router.py` (still a stub; Task 7 implements it):

```python
from collections.abc import Callable

from fastapi import APIRouter, Depends

from src.services.models.user import User


def schedule_router(authenticated_user: Callable[..., User]) -> APIRouter:
    router = APIRouter(prefix="/schedule")

    @router.post("/")
    def schedule_video_download(user: User = Depends(authenticated_user)):
        pass

    return router
```

Replace `src/main.py`:

```python
from fastapi import FastAPI

from src.config.configuration import AppConfiguration
from src.services.authentication_service import CognitoAuthenticationService
from src.services.cognito_helpers import create_cognito_client, get_client_secret
from src.services.system_service import SystemService, SystemServiceImpl
from src.services.user_service import UserService, get_user_service
from src.web.depends.authentication import authenticated_user_dependency
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.authentication_router import authentication_router
from src.web.routers.schedule_router import schedule_router
from src.web.routers.service_router import service_router
from src.web.routers.user_router import user_router
from src.web.routers.video_router import video_router


def create_http_app(app_configuration: AppConfiguration) -> FastAPI:
    app = FastAPI()

    cognito_configuration = app_configuration.cognito
    cognito_idp_client = create_cognito_client(cognito_configuration)
    client_secret = get_client_secret(
        cognito_idp_client,
        cognito_configuration.user_pool_id,
        cognito_configuration.client_id,
    )

    user_service: UserService = get_user_service(app_configuration, cognito_idp_client)
    authentication_service = CognitoAuthenticationService(
        cognito_idp_client, cognito_configuration.client_id, client_secret
    )
    authenticated_user = authenticated_user_dependency(authentication_service)
    system_service: SystemService = SystemServiceImpl(app_configuration)

    app.include_router(user_router(user_service))
    app.include_router(authentication_router(authentication_service))
    app.include_router(schedule_router(authenticated_user))
    app.include_router(video_router())
    app.include_router(service_router(system_service))

    register_exception_handlers(app)

    return app
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.venv/bin/pytest -q`
Expected: all pass.

- [ ] **Step 5: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src tests
git commit -m "Wire Cognito authentication into the fallback API and mount the authentication routes.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Sync messages and contract fixtures

**Files:**
- Create: `src/sync/__init__.py` (empty), `src/sync/timestamps.py`, `src/sync/messages.py`
- Create: `contract/schedule-request.json`, `contract/scheduled-video-upsert.json`,
  `contract/scheduled-video-removal.json`, `contract/request-resolved-scheduled.json`,
  `contract/request-resolved-rejected.json`
- Create: `tests/sync/__init__.py` (empty), `tests/sync/test_messages.py`

**Interfaces:**
- Produces:
  - `iso_millis(value: datetime) -> str` — raises `ValueError` for naive datetimes
  - `Timestamp` — `Annotated[AwareDatetime, PlainSerializer(iso_millis, ...)]`
  - Models (all camelCase aliases, frozen): `ScheduledVideoUpsert`, `ScheduledVideoRemoval`, `ScheduledOutcome`,
    `RejectedOutcome`, `RequestResolved`, `ScheduleRequest`
  - `MainToFallbackMessage` — discriminated union of upsert / removal / resolved
  - `parse_main_to_fallback_message(body: str | bytes) -> MainToFallbackMessage` — raises
    `pydantic.ValidationError`
  - `to_json(message: SyncMessage) -> str` — camelCase, `None` fields omitted

- [ ] **Step 1: Create the contract fixtures**

`contract/scheduled-video-upsert.json`:

```json
{
  "type": "ScheduledVideoUpsert",
  "videoId": "youtube-1a2b3c4d5e6f",
  "capturedAt": "2026-09-26T08:15:30.123Z",
  "hash": "0f1e2d3c4b5a6978",
  "userIds": ["user-1", "user-2"],
  "url": "https://www.youtube.com/watch?v=abc123",
  "videoSite": "YouTube",
  "title": "Sample video",
  "durationMs": 212000,
  "sizeBytes": 48234567,
  "status": "Completed",
  "scheduledAt": "2026-09-25T21:04:11.000Z",
  "completedAt": "2026-09-25T21:09:42.500Z"
}
```

`contract/scheduled-video-removal.json`:

```json
{
  "type": "ScheduledVideoRemoval",
  "videoId": "youtube-1a2b3c4d5e6f",
  "capturedAt": "2026-09-26T08:20:00.000Z"
}
```

`contract/request-resolved-scheduled.json`:

```json
{
  "type": "RequestResolved",
  "requestId": "4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
  "userId": "user-1",
  "outcome": {
    "result": "Scheduled",
    "upsert": {
      "type": "ScheduledVideoUpsert",
      "videoId": "youtube-1a2b3c4d5e6f",
      "capturedAt": "2026-09-26T08:15:30.123Z",
      "hash": "0f1e2d3c4b5a6978",
      "userIds": ["user-1"],
      "url": "https://www.youtube.com/watch?v=abc123",
      "videoSite": "YouTube",
      "title": "Sample video",
      "durationMs": 212000,
      "sizeBytes": 48234567,
      "status": "Queued",
      "scheduledAt": "2026-09-26T08:15:29.000Z"
    }
  }
}
```

`contract/request-resolved-rejected.json`:

```json
{
  "type": "RequestResolved",
  "requestId": "9a0e2b3c-1d4f-4e5a-8b6c-7d8e9f0a1b2c",
  "userId": "user-1",
  "outcome": {
    "result": "Rejected",
    "reason": "Unsupported video site: example.com"
  }
}
```

`contract/schedule-request.json`:

```json
{
  "type": "ScheduleRequest",
  "requestId": "4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
  "userId": "user-1",
  "url": "https://www.youtube.com/watch?v=abc123",
  "requestedAt": "2026-09-26T08:15:00.000Z"
}
```

- [ ] **Step 2: Write the failing tests**

`tests/sync/test_messages.py`:

```python
import json
import unittest
from datetime import UTC, datetime
from pathlib import Path

from pydantic import ValidationError

from src.sync.messages import (
    RejectedOutcome,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
    ScheduledVideoUpsert,
    ScheduleRequest,
    parse_main_to_fallback_message,
    to_json,
)
from src.sync.timestamps import iso_millis

CONTRACT_DIRECTORY = Path(__file__).parent.parent.parent / "contract"


def _fixture(name: str) -> str:
    return (CONTRACT_DIRECTORY / name).read_text()


class TestTimestamps(unittest.TestCase):
    def test_iso_millis_formats_utc_with_milliseconds_and_z(self):
        value = datetime(2026, 9, 26, 8, 15, 30, 123456, tzinfo=UTC)

        self.assertEqual(iso_millis(value), "2026-09-26T08:15:30.123Z")

    def test_iso_millis_rejects_naive_datetimes(self):
        with self.assertRaises(ValueError):
            iso_millis(datetime(2026, 9, 26, 8, 15, 30))


class TestMessages(unittest.TestCase):
    def test_upsert_fixture_parses(self):
        message = parse_main_to_fallback_message(_fixture("scheduled-video-upsert.json"))

        assert isinstance(message, ScheduledVideoUpsert)
        self.assertEqual(message.video_id, "youtube-1a2b3c4d5e6f")
        self.assertEqual(message.user_ids, ["user-1", "user-2"])
        self.assertEqual(message.duration_ms, 212000)
        self.assertEqual(message.captured_at, datetime(2026, 9, 26, 8, 15, 30, 123000, tzinfo=UTC))

    def test_removal_fixture_parses(self):
        message = parse_main_to_fallback_message(_fixture("scheduled-video-removal.json"))

        assert isinstance(message, ScheduledVideoRemoval)

    def test_request_resolved_fixtures_parse(self):
        scheduled = parse_main_to_fallback_message(_fixture("request-resolved-scheduled.json"))
        rejected = parse_main_to_fallback_message(_fixture("request-resolved-rejected.json"))

        assert isinstance(scheduled, RequestResolved)
        assert isinstance(scheduled.outcome, ScheduledOutcome)
        self.assertIsNone(scheduled.outcome.upsert.completed_at)
        assert isinstance(rejected, RequestResolved)
        assert isinstance(rejected.outcome, RejectedOutcome)
        self.assertEqual(rejected.outcome.reason, "Unsupported video site: example.com")

    def test_every_main_to_fallback_fixture_round_trips_exactly(self):
        for name in [
            "scheduled-video-upsert.json",
            "scheduled-video-removal.json",
            "request-resolved-scheduled.json",
            "request-resolved-rejected.json",
        ]:
            with self.subTest(name=name):
                message = parse_main_to_fallback_message(_fixture(name))

                self.assertEqual(json.loads(to_json(message)), json.loads(_fixture(name)))

    def test_schedule_request_serialises_to_the_fixture(self):
        request = ScheduleRequest(
            request_id="4d1c7f0e-8a57-4c1e-9b0b-2f6f3b6f9a10",
            user_id="user-1",
            url="https://www.youtube.com/watch?v=abc123",
            requested_at=datetime(2026, 9, 26, 8, 15, tzinfo=UTC),
        )

        self.assertEqual(json.loads(to_json(request)), json.loads(_fixture("schedule-request.json")))

    def test_unknown_message_type_is_rejected(self):
        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message('{"type": "SomethingElse", "videoId": "v"}')

    def test_naive_timestamp_is_rejected(self):
        body = json.loads(_fixture("scheduled-video-removal.json"))
        body["capturedAt"] = "2026-09-26T08:20:00"

        with self.assertRaises(ValidationError):
            parse_main_to_fallback_message(json.dumps(body))
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/sync/test_messages.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'src.sync'`.

- [ ] **Step 4: Implement**

`src/sync/timestamps.py`:

```python
from datetime import UTC, datetime


def iso_millis(value: datetime) -> str:
    """The single timestamp format used in sync messages and DynamoDB items.

    Fixed-width UTC strings sort lexicographically in time order, which the capturedAt
    guard and the GSI sort keys rely on.
    """
    if value.tzinfo is None:
        raise ValueError("Timestamps must be timezone-aware")

    return value.astimezone(UTC).isoformat(timespec="milliseconds").replace("+00:00", "Z")
```

`src/sync/messages.py`:

```python
from typing import Annotated, Literal

from pydantic import (
    AwareDatetime,
    BaseModel,
    ConfigDict,
    Field,
    PlainSerializer,
    TypeAdapter,
)
from pydantic.alias_generators import to_camel

from src.sync.timestamps import iso_millis

Timestamp = Annotated[
    AwareDatetime, PlainSerializer(iso_millis, return_type=str, when_used="json")
]


class SyncMessage(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True, frozen=True)


class ScheduledVideoUpsert(SyncMessage):
    type: Literal["ScheduledVideoUpsert"] = "ScheduledVideoUpsert"
    video_id: str
    captured_at: Timestamp
    hash: str
    user_ids: list[str]
    url: str
    video_site: str
    title: str
    duration_ms: int
    size_bytes: int
    status: str
    scheduled_at: Timestamp
    completed_at: Timestamp | None = None


class ScheduledVideoRemoval(SyncMessage):
    type: Literal["ScheduledVideoRemoval"] = "ScheduledVideoRemoval"
    video_id: str
    captured_at: Timestamp


class ScheduledOutcome(SyncMessage):
    result: Literal["Scheduled"] = "Scheduled"
    upsert: ScheduledVideoUpsert


class RejectedOutcome(SyncMessage):
    result: Literal["Rejected"] = "Rejected"
    reason: str


class RequestResolved(SyncMessage):
    type: Literal["RequestResolved"] = "RequestResolved"
    request_id: str
    user_id: str
    outcome: Annotated[ScheduledOutcome | RejectedOutcome, Field(discriminator="result")]


class ScheduleRequest(SyncMessage):
    type: Literal["ScheduleRequest"] = "ScheduleRequest"
    request_id: str
    user_id: str
    url: str
    requested_at: Timestamp


MainToFallbackMessage = Annotated[
    ScheduledVideoUpsert | ScheduledVideoRemoval | RequestResolved,
    Field(discriminator="type"),
]

_main_to_fallback_adapter: TypeAdapter[MainToFallbackMessage] = TypeAdapter(
    MainToFallbackMessage
)


def parse_main_to_fallback_message(body: str | bytes) -> MainToFallbackMessage:
    return _main_to_fallback_adapter.validate_json(body)


def to_json(message: SyncMessage) -> str:
    return message.model_dump_json(by_alias=True, exclude_none=True)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.venv/bin/pytest tests/sync/test_messages.py -q`
Expected: all pass.

- [ ] **Step 6: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src/sync tests/sync contract
git commit -m "Add the fallback sync message models and shared contract fixtures.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: DynamoDB items and the sync applier

**Files:**
- Create: `src/sync/items.py`, `src/sync/sync_applier.py`
- Modify: `tests/services/test_service_helpers.py` (add `setup_dynamodb`)
- Create: `tests/sync/sync_test_data.py`, `tests/sync/test_sync_applier.py`

**Interfaces:**
- Consumes: message models and `iso_millis` (Task 3).
- Produces (`src/sync/items.py`):
  - constants `VIDEO_SORT_KEY = "VIDEO"`, `ALL_VIDEOS_PARTITION = "VIDEO"`, `GSI1_NAME = "GSI1"`,
    `DELETED_STATUS = "Deleted"`, `TOMBSTONE_TTL = timedelta(days=1)`, `REJECTED_TTL = timedelta(days=7)`
  - `video_key(video_id) -> dict[str, str]`, `user_partition(user_id) -> str`,
    `link_key(user_id, scheduled_at: str, video_id) -> dict[str, str]`,
    `pending_key(user_id, request_id) -> dict[str, str]`, `epoch_seconds(value: datetime) -> int`
- Produces (`src/sync/sync_applier.py`):
  - `class ApplyResult(StrEnum)`: `APPLIED`, `SKIPPED`
  - `SyncApplier(table, clock: Callable[[], datetime] = ...)` with
    `apply(message: MainToFallbackMessage) -> ApplyResult`
  - exceptions `TooManyWritesError`, `ConcurrentUpdateError`
- Produces (tests): `setup_dynamodb(table_name: str)` → boto3 `Table` with `GSI1`

- [ ] **Step 1: Add the DynamoDB test helper**

Append to `tests/services/test_service_helpers.py`:

```python
def setup_dynamodb(table_name: str):
    dynamodb = boto3.resource("dynamodb", region_name="ap-southeast-2")

    return dynamodb.create_table(
        TableName=table_name,
        BillingMode="PAY_PER_REQUEST",
        KeySchema=[
            {"AttributeName": "PK", "KeyType": "HASH"},
            {"AttributeName": "SK", "KeyType": "RANGE"},
        ],
        AttributeDefinitions=[
            {"AttributeName": name, "AttributeType": "S"}
            for name in ["PK", "SK", "GSI1PK", "GSI1SK"]
        ],
        GlobalSecondaryIndexes=[
            {
                "IndexName": "GSI1",
                "KeySchema": [
                    {"AttributeName": "GSI1PK", "KeyType": "HASH"},
                    {"AttributeName": "GSI1SK", "KeyType": "RANGE"},
                ],
                "Projection": {"ProjectionType": "ALL"},
            }
        ],
    )
```

Create `tests/sync/sync_test_data.py`:

```python
from datetime import UTC, datetime, timedelta
from typing import Any

from src.sync.messages import ScheduledVideoUpsert

FIXED_NOW = datetime(2026, 9, 26, 8, 0, tzinfo=UTC)
T0 = datetime(2026, 9, 26, 7, 0, tzinfo=UTC)


def sample_upsert(**overrides: Any) -> ScheduledVideoUpsert:
    values: dict[str, Any] = {
        "video_id": "youtube-abc",
        "captured_at": T0,
        "hash": "0123456789abcdef",
        "user_ids": ["user-1", "user-2"],
        "url": "https://www.youtube.com/watch?v=abc",
        "video_site": "YouTube",
        "title": "Sample video",
        "duration_ms": 212000,
        "size_bytes": 48234567,
        "status": "Queued",
        "scheduled_at": datetime(2026, 9, 25, 21, 4, 11, tzinfo=UTC),
    }
    values.update(overrides)

    return ScheduledVideoUpsert(**values)


def later(minutes: int) -> datetime:
    return T0 + timedelta(minutes=minutes)
```

- [ ] **Step 2: Write the failing tests**

`tests/sync/test_sync_applier.py`:

```python
import unittest
from datetime import timedelta

from boto3.dynamodb.conditions import Key
from moto import mock_aws

from src.sync.items import epoch_seconds, pending_key, video_key
from src.sync.messages import (
    RejectedOutcome,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
)
from src.sync.sync_applier import ApplyResult, SyncApplier, TooManyWritesError
from tests.services.test_service_helpers import setup_dynamodb
from tests.sync.sync_test_data import FIXED_NOW, T0, later, sample_upsert

SCHEDULED_AT = "2026-09-25T21:04:11.000Z"


@mock_aws
class TestSyncApplier(unittest.TestCase):
    def setUp(self):
        self.table = setup_dynamodb("scheduled-videos")
        self.applier = SyncApplier(self.table, clock=lambda: FIXED_NOW)

    def _video(self, video_id: str = "youtube-abc") -> dict | None:
        return self.table.get_item(Key=video_key(video_id)).get("Item")

    def _links(self, user_id: str) -> list[dict]:
        return self.table.query(
            KeyConditionExpression=Key("PK").eq(f"USER#{user_id}")
            & Key("SK").begins_with("VIDEO#")
        )["Items"]

    def _put_pending(self, user_id: str, request_id: str) -> None:
        self.table.put_item(
            Item={
                **pending_key(user_id, request_id),
                "requestId": request_id,
                "url": "https://www.youtube.com/watch?v=abc",
                "requestedAt": "2026-09-26T06:59:00.000Z",
                "status": "Pending",
            }
        )

    def test_new_upsert_writes_the_video_and_one_link_per_user(self):
        result = self.applier.apply(sample_upsert())

        self.assertEqual(result, ApplyResult.APPLIED)
        video = self._video()
        assert video is not None
        self.assertEqual(video["userIds"], ["user-1", "user-2"])
        self.assertEqual(video["capturedAt"], "2026-09-26T07:00:00.000Z")
        self.assertEqual(video["GSI1PK"], "VIDEO")
        self.assertEqual(video["GSI1SK"], f"{SCHEDULED_AT}#youtube-abc")
        self.assertFalse(video["deleted"])
        for user_id in ["user-1", "user-2"]:
            links = self._links(user_id)
            self.assertEqual([link["SK"] for link in links], [f"VIDEO#{SCHEDULED_AT}#youtube-abc"])
            self.assertEqual(links[0]["title"], "Sample video")
            self.assertEqual(int(links[0]["durationMs"]), 212000)

    def test_equal_or_older_captured_at_is_skipped(self):
        self.applier.apply(sample_upsert(captured_at=later(5), title="Newer"))

        equal = self.applier.apply(sample_upsert(captured_at=later(5), title="Equal"))
        older = self.applier.apply(sample_upsert(captured_at=later(1), title="Older"))

        self.assertEqual((equal, older), (ApplyResult.SKIPPED, ApplyResult.SKIPPED))
        video = self._video()
        assert video is not None
        self.assertEqual(video["title"], "Newer")

    def test_removed_user_loses_their_link(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), user_ids=["user-1"]))

        self.assertEqual(len(self._links("user-1")), 1)
        self.assertEqual(self._links("user-2"), [])

    def test_upsert_with_no_users_keeps_the_video_without_links(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), user_ids=[]))

        video = self._video()
        assert video is not None
        self.assertEqual(video["userIds"], [])
        self.assertEqual(self._links("user-1"), [])

    def test_removal_deletes_links_and_leaves_a_tombstone(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(1)))

        video = self._video()
        assert video is not None
        self.assertTrue(video["deleted"])
        self.assertNotIn("GSI1PK", video)
        self.assertEqual(int(video["ttl"]), epoch_seconds(FIXED_NOW + timedelta(days=1)))
        self.assertEqual(self._links("user-1"), [])

    def test_upsert_with_deleted_status_is_treated_as_removal(self):
        self.applier.apply(sample_upsert())

        self.applier.apply(sample_upsert(captured_at=later(1), status="Deleted"))

        video = self._video()
        assert video is not None
        self.assertTrue(video["deleted"])
        self.assertEqual(self._links("user-2"), [])

    def test_late_upsert_does_not_resurrect_a_tombstone(self):
        self.applier.apply(ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(5)))

        result = self.applier.apply(sample_upsert(captured_at=later(1)))

        self.assertEqual(result, ApplyResult.SKIPPED)
        self.assertEqual(self._links("user-1"), [])

    def test_newer_upsert_after_a_tombstone_recreates_the_video(self):
        self.applier.apply(ScheduledVideoRemoval(video_id="youtube-abc", captured_at=later(1)))

        self.applier.apply(sample_upsert(captured_at=later(5)))

        video = self._video()
        assert video is not None
        self.assertFalse(video["deleted"])
        self.assertNotIn("ttl", video)
        self.assertEqual(len(self._links("user-1")), 1)

    def test_request_resolved_scheduled_replaces_the_pending_item(self):
        self._put_pending("user-1", "request-1")

        self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=ScheduledOutcome(upsert=sample_upsert(user_ids=["user-1"])),
            )
        )

        self.assertNotIn("Item", self.table.get_item(Key=pending_key("user-1", "request-1")))
        self.assertEqual(len(self._links("user-1")), 1)

    def test_request_resolved_scheduled_deletes_pending_even_when_the_upsert_is_stale(self):
        self.applier.apply(sample_upsert(captured_at=later(10)))
        self._put_pending("user-1", "request-1")

        result = self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=ScheduledOutcome(upsert=sample_upsert(captured_at=later(1))),
            )
        )

        self.assertEqual(result, ApplyResult.SKIPPED)
        self.assertNotIn("Item", self.table.get_item(Key=pending_key("user-1", "request-1")))

    def test_request_resolved_rejected_marks_the_pending_item(self):
        self._put_pending("user-1", "request-1")

        self.applier.apply(
            RequestResolved(
                request_id="request-1",
                user_id="user-1",
                outcome=RejectedOutcome(reason="Unsupported video site"),
            )
        )

        pending = self.table.get_item(Key=pending_key("user-1", "request-1"))["Item"]
        self.assertEqual(pending["status"], "Rejected")
        self.assertEqual(pending["reason"], "Unsupported video site")
        self.assertEqual(int(pending["ttl"]), epoch_seconds(FIXED_NOW + timedelta(days=7)))

    def test_rejection_for_a_missing_pending_item_creates_nothing(self):
        self.applier.apply(
            RequestResolved(
                request_id="missing",
                user_id="user-1",
                outcome=RejectedOutcome(reason="Unsupported video site"),
            )
        )

        self.assertNotIn("Item", self.table.get_item(Key=pending_key("user-1", "missing")))

    def test_more_writes_than_a_transaction_allows_raises(self):
        user_ids = [f"user-{index}" for index in range(100)]

        with self.assertRaises(TooManyWritesError):
            self.applier.apply(sample_upsert(user_ids=user_ids, captured_at=T0))
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/sync/test_sync_applier.py -q`
Expected: FAIL — `ModuleNotFoundError: No module named 'src.sync.items'`.

- [ ] **Step 4: Implement**

`src/sync/items.py`:

```python
from collections.abc import Mapping
from datetime import datetime, timedelta
from typing import Any

from src.sync.messages import ScheduledVideoUpsert
from src.sync.timestamps import iso_millis

VIDEO_SORT_KEY = "VIDEO"
ALL_VIDEOS_PARTITION = "VIDEO"
GSI1_NAME = "GSI1"
DELETED_STATUS = "Deleted"
TOMBSTONE_TTL = timedelta(days=1)
REJECTED_TTL = timedelta(days=7)


def video_key(video_id: str) -> dict[str, str]:
    return {"PK": f"VIDEO#{video_id}", "SK": VIDEO_SORT_KEY}


def user_partition(user_id: str) -> str:
    return f"USER#{user_id}"


def link_key(user_id: str, scheduled_at: str, video_id: str) -> dict[str, str]:
    return {"PK": user_partition(user_id), "SK": f"VIDEO#{scheduled_at}#{video_id}"}


def pending_key(user_id: str, request_id: str) -> dict[str, str]:
    return {"PK": user_partition(user_id), "SK": f"PENDING#{request_id}"}


def epoch_seconds(value: datetime) -> int:
    return int(value.timestamp())


def display_fields(upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    fields: dict[str, Any] = {
        "videoId": upsert.video_id,
        "url": upsert.url,
        "videoSite": upsert.video_site,
        "title": upsert.title,
        "durationMs": upsert.duration_ms,
        "sizeBytes": upsert.size_bytes,
        "status": upsert.status,
        "scheduledAt": iso_millis(upsert.scheduled_at),
    }

    if upsert.completed_at is not None:
        fields["completedAt"] = iso_millis(upsert.completed_at)

    return fields


def link_item(user_id: str, upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    scheduled_at = iso_millis(upsert.scheduled_at)

    return {**link_key(user_id, scheduled_at, upsert.video_id), **display_fields(upsert)}


def video_item(upsert: ScheduledVideoUpsert) -> dict[str, Any]:
    scheduled_at = iso_millis(upsert.scheduled_at)

    return {
        **video_key(upsert.video_id),
        **display_fields(upsert),
        "userIds": sorted(set(upsert.user_ids)),
        "capturedAt": iso_millis(upsert.captured_at),
        "hash": upsert.hash,
        "deleted": False,
        "GSI1PK": ALL_VIDEOS_PARTITION,
        "GSI1SK": f"{scheduled_at}#{upsert.video_id}",
    }


def tombstone_item(video_id: str, captured_at: datetime, now: datetime) -> dict[str, Any]:
    return {
        **video_key(video_id),
        "videoId": video_id,
        "capturedAt": iso_millis(captured_at),
        "deleted": True,
        "ttl": epoch_seconds(now + TOMBSTONE_TTL),
    }


def live_link_keys(
    current: Mapping[str, Any] | None,
    video_id: str,
    keep: frozenset[str] = frozenset(),
) -> list[dict[str, str]]:
    """Keys of the user links a stored video item currently has, except those in `keep`."""
    if current is None or current.get("deleted"):
        return []

    return [
        link_key(user_id, current["scheduledAt"], video_id)
        for user_id in sorted(current["userIds"])
        if user_id not in keep
    ]
```

`src/sync/sync_applier.py`:

```python
import logging
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from enum import StrEnum
from typing import Any

from src.sync.items import (
    DELETED_STATUS,
    REJECTED_TTL,
    epoch_seconds,
    link_item,
    live_link_keys,
    pending_key,
    tombstone_item,
    video_item,
    video_key,
)
from src.sync.messages import (
    MainToFallbackMessage,
    RejectedOutcome,
    RequestResolved,
    ScheduledOutcome,
    ScheduledVideoRemoval,
    ScheduledVideoUpsert,
)
from src.sync.timestamps import iso_millis

logger = logging.getLogger(__name__)

MAX_TRANSACTION_WRITES = 100

Write = dict[str, Any]
BuildWrites = Callable[[Mapping[str, Any] | None], tuple[list[Write], dict[str, Any]]]


class ApplyResult(StrEnum):
    APPLIED = "Applied"
    SKIPPED = "Skipped"


class TooManyWritesError(Exception):
    pass


class ConcurrentUpdateError(Exception):
    pass


def _utc_now() -> datetime:
    return datetime.now(UTC)


class SyncApplier:
    MAX_ATTEMPTS = 3

    def __init__(self, table, clock: Callable[[], datetime] = _utc_now):
        self._table = table
        self._table_name: str = table.name
        self._client = table.meta.client
        self._clock = clock

    def apply(self, message: MainToFallbackMessage) -> ApplyResult:
        match message:
            case ScheduledVideoUpsert():
                return self._apply_upsert(message, [])
            case ScheduledVideoRemoval():
                return self._apply_removal(message.video_id, message.captured_at, [])
            case RequestResolved(outcome=ScheduledOutcome() as outcome):
                resolve = [self._delete(pending_key(message.user_id, message.request_id))]
                return self._apply_upsert(outcome.upsert, resolve)
            case RequestResolved(outcome=RejectedOutcome() as outcome):
                self._reject_pending(message.user_id, message.request_id, outcome.reason)
                return ApplyResult.APPLIED

        raise TypeError(f"Unsupported sync message: {type(message).__name__}")

    def _apply_upsert(
        self, upsert: ScheduledVideoUpsert, extra_writes: list[Write]
    ) -> ApplyResult:
        if upsert.status == DELETED_STATUS:
            return self._apply_removal(upsert.video_id, upsert.captured_at, extra_writes)

        new_users = frozenset(upsert.user_ids)

        def build(current: Mapping[str, Any] | None) -> tuple[list[Write], dict[str, Any]]:
            writes = [self._put(link_item(user_id, upsert)) for user_id in sorted(new_users)]
            writes += [
                self._delete(key)
                for key in live_link_keys(current, upsert.video_id, keep=new_users)
            ]
            return writes, video_item(upsert)

        return self._apply_video_change(
            upsert.video_id, upsert.captured_at, build, extra_writes
        )

    def _apply_removal(
        self, video_id: str, captured_at: datetime, extra_writes: list[Write]
    ) -> ApplyResult:
        def build(current: Mapping[str, Any] | None) -> tuple[list[Write], dict[str, Any]]:
            writes = [self._delete(key) for key in live_link_keys(current, video_id)]
            return writes, tombstone_item(video_id, captured_at, self._clock())

        return self._apply_video_change(video_id, captured_at, build, extra_writes)

    def _apply_video_change(
        self,
        video_id: str,
        captured_at: datetime,
        build: BuildWrites,
        extra_writes: list[Write],
    ) -> ApplyResult:
        incoming = iso_millis(captured_at)

        for _ in range(self.MAX_ATTEMPTS):
            current = self._table.get_item(
                Key=video_key(video_id), ConsistentRead=True
            ).get("Item")

            if current is not None and current["capturedAt"] >= incoming:
                # Stale message: keep the stored state, but still resolve any pending request.
                if extra_writes:
                    self._transact(extra_writes)
                return ApplyResult.SKIPPED

            writes, new_video_item = build(current)
            video_put = self._put(new_video_item)
            if current is None:
                video_put["Put"]["ConditionExpression"] = "attribute_not_exists(PK)"
            else:
                video_put["Put"]["ConditionExpression"] = "capturedAt = :expected"
                video_put["Put"]["ExpressionAttributeValues"] = {
                    ":expected": current["capturedAt"]
                }

            try:
                self._transact([video_put, *writes, *extra_writes])
                return ApplyResult.APPLIED
            except self._client.exceptions.TransactionCanceledException as error:
                if not _condition_check_failed(error):
                    raise
                # Another invocation changed the video since we read it: re-read and retry.

        raise ConcurrentUpdateError(video_id)

    def _reject_pending(self, user_id: str, request_id: str, reason: str) -> None:
        try:
            self._table.update_item(
                Key=pending_key(user_id, request_id),
                UpdateExpression="SET #status = :rejected, #reason = :reason, #ttl = :ttl",
                ConditionExpression="attribute_exists(PK)",
                ExpressionAttributeNames={
                    "#status": "status",
                    "#reason": "reason",
                    "#ttl": "ttl",
                },
                ExpressionAttributeValues={
                    ":rejected": "Rejected",
                    ":reason": reason,
                    ":ttl": epoch_seconds(self._clock() + REJECTED_TTL),
                },
            )
        except self._client.exceptions.ConditionalCheckFailedException:
            logger.info("No pending item for rejected request %s", request_id)

    def _transact(self, writes: list[Write]) -> None:
        if len(writes) > MAX_TRANSACTION_WRITES:
            raise TooManyWritesError(
                f"{len(writes)} writes exceed the DynamoDB transaction limit"
            )

        self._client.transact_write_items(TransactItems=writes)

    def _put(self, item: dict[str, Any]) -> Write:
        return {"Put": {"TableName": self._table_name, "Item": item}}

    def _delete(self, key: dict[str, str]) -> Write:
        return {"Delete": {"TableName": self._table_name, "Key": key}}


def _condition_check_failed(error: Exception) -> bool:
    reasons = getattr(error, "response", {}).get("CancellationReasons", [])
    return any(reason.get("Code") == "ConditionalCheckFailed" for reason in reasons)
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.venv/bin/pytest tests/sync -q`
Expected: all pass.

- [ ] **Step 6: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src/sync tests
git commit -m "Apply main-side sync messages to DynamoDB with a capturedAt-guarded transaction.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: SQS batch processing and the SyncFunction entry point

**Files:**
- Create: `src/sync/sqs_batch.py`
- Create: `src/config/sync_configuration.py`
- Create: `sync_handler.py` (repository root of `fallback-api/`)
- Modify: `application.conf`
- Create: `tests/sync/test_sqs_batch.py`, `tests/test_sync_handler.py`

**Interfaces:**
- Consumes: `SyncApplier`, `parse_main_to_fallback_message` (Tasks 3–4).
- Produces:
  - `process_sqs_batch(event: Mapping[str, Any], applier: SyncApplier) -> dict[str, list[dict[str, str]]]`
  - `SyncConfiguration(table_name: str, fallback_to_main_queue_url: str)` with `parse(config_tree)`
  - `sync_handler.handler(event, context)` — Lambda handler `sync_handler.handler`

- [ ] **Step 1: Write the failing tests**

`tests/sync/test_sqs_batch.py`:

```python
import unittest
from unittest.mock import MagicMock

from src.sync.messages import ScheduledVideoRemoval, to_json
from src.sync.sqs_batch import process_sqs_batch
from tests.sync.sync_test_data import T0, sample_upsert


def _record(message_id: str, body: str) -> dict:
    return {"messageId": message_id, "body": body}


class TestProcessSqsBatch(unittest.TestCase):
    def test_all_records_applied_reports_no_failures(self):
        applier = MagicMock()
        event = {
            "Records": [
                _record("m1", to_json(sample_upsert())),
                _record("m2", to_json(ScheduledVideoRemoval(video_id="v", captured_at=T0))),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(response, {"batchItemFailures": []})
        self.assertEqual(applier.apply.call_count, 2)

    def test_malformed_and_unknown_messages_fail_only_their_own_record(self):
        applier = MagicMock()
        event = {
            "Records": [
                _record("bad-json", "{not json"),
                _record("unknown-type", '{"type": "SomethingElse"}'),
                _record("good", to_json(sample_upsert())),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(
            response,
            {
                "batchItemFailures": [
                    {"itemIdentifier": "bad-json"},
                    {"itemIdentifier": "unknown-type"},
                ]
            },
        )
        applier.apply.assert_called_once()

    def test_an_apply_error_fails_only_that_record(self):
        applier = MagicMock()
        applier.apply.side_effect = [RuntimeError("boom"), None]
        event = {
            "Records": [
                _record("m1", to_json(sample_upsert())),
                _record("m2", to_json(sample_upsert(video_id="other"))),
            ]
        }

        response = process_sqs_batch(event, applier)

        self.assertEqual(response, {"batchItemFailures": [{"itemIdentifier": "m1"}]})
```

`tests/test_sync_handler.py`:

```python
import importlib
import sys
import unittest

import pytest
from moto import mock_aws

from tests.services.test_service_helpers import setup_dynamodb


@mock_aws
class TestSyncHandler(unittest.TestCase):
    @pytest.fixture(autouse=True)
    def _environment(self, monkeypatch):
        monkeypatch.setenv("SCHEDULED_VIDEOS_TABLE_NAME", "scheduled-videos")
        monkeypatch.setenv("FALLBACK_TO_MAIN_QUEUE_URL", "https://sqs.example.com/queue")

    def test_handler_processes_an_empty_batch(self):
        setup_dynamodb("scheduled-videos")
        sys.modules.pop("sync_handler", None)
        sync_handler = importlib.import_module("sync_handler")

        self.assertEqual(sync_handler.handler({"Records": []}, None), {"batchItemFailures": []})
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/sync/test_sqs_batch.py tests/test_sync_handler.py -q`
Expected: FAIL — `ModuleNotFoundError` for `src.sync.sqs_batch` and `sync_handler`.

- [ ] **Step 3: Implement**

`src/sync/sqs_batch.py`:

```python
import logging
from collections.abc import Mapping
from typing import Any

from src.sync.messages import parse_main_to_fallback_message
from src.sync.sync_applier import SyncApplier

logger = logging.getLogger(__name__)


def process_sqs_batch(
    event: Mapping[str, Any], applier: SyncApplier
) -> dict[str, list[dict[str, str]]]:
    """Apply each record on its own; failed records are retried by SQS, then dead-lettered."""
    failures: list[dict[str, str]] = []

    for record in event.get("Records", []):
        message_id: str = record["messageId"]
        try:
            applier.apply(parse_main_to_fallback_message(record["body"]))
        except Exception:
            logger.exception("Failed to apply sync message %s", message_id)
            failures.append({"itemIdentifier": message_id})

    return {"batchItemFailures": failures}
```

`src/config/sync_configuration.py`:

```python
from pydantic import BaseModel
from pyhocon import ConfigTree


class SyncConfiguration(BaseModel):
    table_name: str
    fallback_to_main_queue_url: str

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "SyncConfiguration":
        sync_config: ConfigTree = config_tree["sync"]

        return SyncConfiguration(
            table_name=sync_config["table-name"],
            fallback_to_main_queue_url=sync_config["fallback-to-main-queue-url"],
        )
```

Append to `application.conf`:

```hocon

sync {
    table-name = ${?SCHEDULED_VIDEOS_TABLE_NAME}
    fallback-to-main-queue-url = ${?FALLBACK_TO_MAIN_QUEUE_URL}
}
```

`sync_handler.py`:

```python
import boto3

from src.config.configuration import get_config_tree
from src.config.sync_configuration import SyncConfiguration
from src.sync.sqs_batch import process_sqs_batch
from src.sync.sync_applier import SyncApplier

sync_configuration: SyncConfiguration = SyncConfiguration.parse(get_config_tree())
sync_applier = SyncApplier(boto3.resource("dynamodb").Table(sync_configuration.table_name))


def handler(event, context):
    return process_sqs_batch(event, sync_applier)
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.venv/bin/pytest -q`
Expected: all pass.

- [ ] **Step 5: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src sync_handler.py application.conf tests
git commit -m "Add the SyncFunction Lambda entry point with partial batch failure reporting.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Scheduling service (schedule and list)

**Files:**
- Modify: `src/config/configuration.py`, `src/dev/run.py`
- Modify: `src/services/exceptions.py`
- Create: `src/sync/page_tokens.py`, `src/services/models/scheduled_video.py`
- Modify: `src/services/scheduling_service.py` (rewrite)
- Modify: `tests/services/test_service_helpers.py` (add `setup_sqs`), `tests/test_main.py`
- Create: `tests/services/test_scheduling_service.py`

**Interfaces:**
- Consumes: `SyncConfiguration` (Task 5); items helpers and `SyncApplier` (Task 4); `ScheduleRequest`, `to_json`,
  `iso_millis` (Task 3); `User`, `Role` (Task 1).
- Produces:
  - `AppConfiguration` gains `sync: SyncConfiguration`
  - exceptions `InvalidUrlException`, `InvalidPageTokenException`, `ServiceUnavailableException`
  - `encode_page_token(key: Mapping[str, Any]) -> str`, `decode_page_token(token: str) -> dict[str, str]`
  - `VideoSummary`, `PendingRequest`, `ScheduleListing` (camelCase aliases; `from_item` constructors)
  - `SchedulingService` with `schedule(url: str, user: User) -> str` and
    `list_schedules(user: User, status: str | None, page_token: str | None) -> ScheduleListing`
  - `DynamoDbSchedulingService(table, sqs_client, fallback_to_main_queue_url: str, clock=..., request_id_generator=...)`
  - `get_scheduling_service(app_configuration: AppConfiguration) -> SchedulingService`
  - `PAGE_SIZE = 25`

- [ ] **Step 1: Add the SQS test helper and update `tests/test_main.py`**

Append to `tests/services/test_service_helpers.py`:

```python
def setup_sqs(queue_name: str) -> tuple[Any, str]:
    sqs_client = boto3.client("sqs", region_name="ap-southeast-2")
    queue_url = sqs_client.create_queue(QueueName=queue_name)["QueueUrl"]

    return sqs_client, queue_url
```

In `tests/test_main.py`, import `SyncConfiguration` and add the field to `app_configuration_for_tests`:

```python
        sync=SyncConfiguration(
            table_name="scheduled-videos",
            fallback_to_main_queue_url="https://sqs.ap-southeast-2.amazonaws.com/000000000000/q",
        ),
```

- [ ] **Step 2: Write the failing tests**

`tests/services/test_scheduling_service.py`:

```python
import json
import unittest
from datetime import timedelta

from moto import mock_aws

from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.user import Role, User
from src.services.scheduling_service import PAGE_SIZE, DynamoDbSchedulingService
from src.sync.items import pending_key
from src.sync.messages import ScheduledVideoRemoval
from src.sync.page_tokens import encode_page_token
from src.sync.sync_applier import SyncApplier
from tests.services.test_service_helpers import setup_dynamodb, setup_sqs
from tests.sync.sync_test_data import FIXED_NOW, T0, sample_upsert

USER = User(id="user-1", email="u1@ruchij.com", first_name="U", last_name="One")
OTHER = User(id="user-2", email="u2@ruchij.com", first_name="U", last_name="Two")
ADMIN = User(
    id="admin-1", email="a@ruchij.com", first_name="A", last_name="D", role=Role.ADMIN
)


@mock_aws
class TestDynamoDbSchedulingService(unittest.TestCase):
    def setUp(self):
        self.table = setup_dynamodb("scheduled-videos")
        self.sqs_client, self.queue_url = setup_sqs("fallback-to-main")
        self.applier = SyncApplier(self.table, clock=lambda: FIXED_NOW)
        self.service = DynamoDbSchedulingService(
            self.table,
            self.sqs_client,
            self.queue_url,
            clock=lambda: FIXED_NOW,
            request_id_generator=lambda: "request-1",
        )

    def _video(self, video_id: str, user_ids: list[str], minutes: int, status: str = "Queued"):
        self.applier.apply(
            sample_upsert(
                video_id=video_id,
                user_ids=user_ids,
                status=status,
                scheduled_at=T0 + timedelta(minutes=minutes),
            )
        )

    def _queued_bodies(self) -> list[dict]:
        response = self.sqs_client.receive_message(
            QueueUrl=self.queue_url, MaxNumberOfMessages=10
        )
        return [json.loads(message["Body"]) for message in response.get("Messages", [])]

    def test_schedule_queues_a_request_and_stores_a_pending_item(self):
        request_id = self.service.schedule("  https://www.youtube.com/watch?v=abc ", USER)

        self.assertEqual(request_id, "request-1")
        self.assertEqual(
            self._queued_bodies(),
            [
                {
                    "type": "ScheduleRequest",
                    "requestId": "request-1",
                    "userId": "user-1",
                    "url": "https://www.youtube.com/watch?v=abc",
                    "requestedAt": "2026-09-26T08:00:00.000Z",
                }
            ],
        )
        pending = self.table.get_item(Key=pending_key("user-1", "request-1"))["Item"]
        self.assertEqual(pending["status"], "Pending")

    def test_schedule_rejects_invalid_urls_without_queueing(self):
        for url in ["", "not a url", "ftp://example.com/video", "https://", "https://a b.com"]:
            with self.subTest(url=url), self.assertRaises(InvalidUrlException):
                self.service.schedule(url, USER)

        self.assertEqual(self._queued_bodies(), [])

    def test_schedule_raises_service_unavailable_when_sqs_fails_and_writes_nothing(self):
        service = DynamoDbSchedulingService(
            self.table,
            self.sqs_client,
            self.queue_url + "-missing",
            clock=lambda: FIXED_NOW,
            request_id_generator=lambda: "request-1",
        )

        with self.assertRaises(ServiceUnavailableException):
            service.schedule("https://www.youtube.com/watch?v=abc", USER)

        self.assertNotIn("Item", self.table.get_item(Key=pending_key("user-1", "request-1")))

    def test_new_user_gets_an_empty_listing(self):
        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual((listing.videos, listing.pending, listing.next_page_token), ([], [], None))

    def test_user_sees_only_their_videos_newest_first_and_their_pending_requests(self):
        self._video("old", ["user-1"], minutes=1)
        self._video("new", ["user-1", "user-2"], minutes=2)
        self._video("others", ["user-2"], minutes=3)
        self.service.schedule("https://www.youtube.com/watch?v=pending", USER)

        listing = self.service.list_schedules(USER, None, None)

        self.assertEqual([v.video_id for v in listing.videos], ["new", "old"])
        self.assertEqual([p.request_id for p in listing.pending], ["request-1"])

    def test_admin_sees_all_live_videos_but_only_their_own_pending_requests(self):
        self._video("a", ["user-1"], minutes=1)
        self._video("b", ["user-2"], minutes=2)
        self._video("gone", ["user-2"], minutes=3)
        self.applier.apply(
            ScheduledVideoRemoval(video_id="gone", captured_at=T0 + timedelta(hours=1))
        )
        self.service.schedule("https://www.youtube.com/watch?v=x", OTHER)

        listing = self.service.list_schedules(ADMIN, None, None)

        self.assertEqual([v.video_id for v in listing.videos], ["b", "a"])
        self.assertEqual(listing.pending, [])

    def test_status_filter(self):
        self._video("queued", ["user-1"], minutes=1)
        self._video("done", ["user-1"], minutes=2, status="Completed")

        listing = self.service.list_schedules(USER, "Completed", None)

        self.assertEqual([v.video_id for v in listing.videos], ["done"])

    def test_pagination(self):
        for index in range(PAGE_SIZE + 5):
            self._video(f"video-{index:02d}", ["user-1"], minutes=index)

        first = self.service.list_schedules(USER, None, None)
        assert first.next_page_token is not None
        second = self.service.list_schedules(USER, None, first.next_page_token)

        self.assertEqual(len(first.videos), PAGE_SIZE)
        self.assertEqual(len(second.videos), 5)
        self.assertIsNone(second.next_page_token)
        self.assertEqual(second.videos[-1].video_id, "video-00")

    def test_malformed_page_token_is_rejected(self):
        for token in ["!!!", "bm90LWpzb24=", encode_page_token({"PK": 1})]:
            with self.subTest(token=token), self.assertRaises(InvalidPageTokenException):
                self.service.list_schedules(USER, None, token)

    def test_page_token_from_another_users_partition_is_rejected(self):
        token = encode_page_token({"PK": "USER#user-2", "SK": "VIDEO#2026#v"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(USER, None, token)

    def test_user_page_token_is_rejected_for_the_admin_index(self):
        token = encode_page_token({"PK": "USER#admin-1", "SK": "VIDEO#2026#v"})

        with self.assertRaises(InvalidPageTokenException):
            self.service.list_schedules(ADMIN, None, token)

```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/services/test_scheduling_service.py -q`
Expected: FAIL — `ImportError` for the new exceptions / `DynamoDbSchedulingService`.

- [ ] **Step 4: Implement**

Append to `src/services/exceptions.py`:

```python
class InvalidUrlException(Exception):
    pass


class InvalidPageTokenException(Exception):
    pass


class ServiceUnavailableException(Exception):
    pass
```

`src/sync/page_tokens.py`:

```python
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
        isinstance(name, str) and isinstance(value, str) for name, value in decoded.items()
    ):
        raise InvalidPageTokenException("Malformed page token")

    return decoded
```

`src/services/models/scheduled_video.py`:

```python
from collections.abc import Mapping
from typing import Any

from pydantic import BaseModel, ConfigDict
from pydantic.alias_generators import to_camel


class CamelModel(BaseModel):
    model_config = ConfigDict(alias_generator=to_camel, populate_by_name=True)


class VideoSummary(CamelModel):
    video_id: str
    url: str
    video_site: str
    title: str
    duration_ms: int
    size_bytes: int
    status: str
    scheduled_at: str
    completed_at: str | None = None

    @classmethod
    def from_item(cls, item: Mapping[str, Any]) -> "VideoSummary":
        return cls(
            video_id=item["videoId"],
            url=item["url"],
            video_site=item["videoSite"],
            title=item["title"],
            duration_ms=int(item["durationMs"]),
            size_bytes=int(item["sizeBytes"]),
            status=item["status"],
            scheduled_at=item["scheduledAt"],
            completed_at=item.get("completedAt"),
        )


class PendingRequest(CamelModel):
    request_id: str
    url: str
    requested_at: str
    status: str
    reason: str | None = None

    @classmethod
    def from_item(cls, item: Mapping[str, Any]) -> "PendingRequest":
        return cls(
            request_id=item["requestId"],
            url=item["url"],
            requested_at=item["requestedAt"],
            status=item["status"],
            reason=item.get("reason"),
        )


class ScheduleListing(CamelModel):
    videos: list[VideoSummary]
    pending: list[PendingRequest]
    next_page_token: str | None = None
```

Replace `src/services/scheduling_service.py`:

```python
from abc import ABC, abstractmethod
from collections.abc import Callable, Mapping
from datetime import UTC, datetime
from typing import Any
from urllib.parse import urlparse
from uuid import uuid4

import boto3
from boto3.dynamodb.conditions import Attr, Key
from botocore.exceptions import BotoCoreError, ClientError

from src.config.configuration import AppConfiguration
from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.scheduled_video import (
    PendingRequest,
    ScheduleListing,
    VideoSummary,
)
from src.services.models.user import Role, User
from src.sync.items import ALL_VIDEOS_PARTITION, GSI1_NAME, pending_key, user_partition
from src.sync.messages import ScheduleRequest, to_json
from src.sync.page_tokens import decode_page_token, encode_page_token
from src.sync.timestamps import iso_millis

PAGE_SIZE = 25


class SchedulingService(ABC):
    @abstractmethod
    def schedule(self, url: str, user: User) -> str:
        """Queue a schedule request for the main API and return its request id."""

    @abstractmethod
    def list_schedules(
        self, user: User, status: str | None, page_token: str | None
    ) -> ScheduleListing:
        pass


def is_http_url(value: str) -> bool:
    parsed = urlparse(value)

    return (
        parsed.scheme in ("http", "https")
        and bool(parsed.netloc)
        and not any(character.isspace() for character in value)
    )


def _utc_now() -> datetime:
    return datetime.now(UTC)


def _new_request_id() -> str:
    return str(uuid4())


class DynamoDbSchedulingService(SchedulingService):
    def __init__(
        self,
        table,
        sqs_client,
        fallback_to_main_queue_url: str,
        clock: Callable[[], datetime] = _utc_now,
        request_id_generator: Callable[[], str] = _new_request_id,
    ):
        self._table = table
        self._sqs_client = sqs_client
        self._fallback_to_main_queue_url = fallback_to_main_queue_url
        self._clock = clock
        self._request_id_generator = request_id_generator

    def schedule(self, url: str, user: User) -> str:
        url = url.strip()
        if not is_http_url(url):
            raise InvalidUrlException(f'"{url}" is not an absolute http(s) URL')

        request = ScheduleRequest(
            request_id=self._request_id_generator(),
            user_id=user.id,
            url=url,
            requested_at=self._clock(),
        )

        # Queue first: if the pending write below fails, the request is still processed and its
        # result simply finds no pending item. The reverse order could strand a pending item.
        try:
            self._sqs_client.send_message(
                QueueUrl=self._fallback_to_main_queue_url, MessageBody=to_json(request)
            )
        except (BotoCoreError, ClientError) as error:
            raise ServiceUnavailableException("Unable to queue the schedule request") from error

        self._table.put_item(
            Item={
                **pending_key(user.id, request.request_id),
                "requestId": request.request_id,
                "url": url,
                "requestedAt": iso_millis(request.requested_at),
                "status": "Pending",
            }
        )

        return request.request_id

    def list_schedules(
        self, user: User, status: str | None, page_token: str | None
    ) -> ScheduleListing:
        query: dict[str, Any] = {"Limit": PAGE_SIZE, "ScanIndexForward": False}

        if user.role == Role.ADMIN:
            query["IndexName"] = GSI1_NAME
            query["KeyConditionExpression"] = Key("GSI1PK").eq(ALL_VIDEOS_PARTITION)
            required: Mapping[str, str] = {"GSI1PK": ALL_VIDEOS_PARTITION}
            sort_key_prefix: str | None = None
        else:
            partition = user_partition(user.id)
            query["KeyConditionExpression"] = Key("PK").eq(partition) & Key(
                "SK"
            ).begins_with("VIDEO#")
            required = {"PK": partition}
            sort_key_prefix = "VIDEO#"

        if status is not None:
            query["FilterExpression"] = Attr("status").eq(status)

        if page_token is not None:
            query["ExclusiveStartKey"] = self._start_key(
                page_token, required, sort_key_prefix
            )

        response = self._table.query(**query)
        last_evaluated_key = response.get("LastEvaluatedKey")

        return ScheduleListing(
            videos=[VideoSummary.from_item(item) for item in response["Items"]],
            pending=self._pending_requests(user.id),
            next_page_token=(
                None if last_evaluated_key is None else encode_page_token(last_evaluated_key)
            ),
        )

    @staticmethod
    def _start_key(
        page_token: str, required: Mapping[str, str], sort_key_prefix: str | None
    ) -> dict[str, str]:
        """Reject tokens that point outside the caller's own query, e.g. another user's partition."""
        start_key = decode_page_token(page_token)

        if any(start_key.get(name) != value for name, value in required.items()):
            raise InvalidPageTokenException("Page token does not belong to this listing")

        if sort_key_prefix is not None and not start_key.get("SK", "").startswith(
            sort_key_prefix
        ):
            raise InvalidPageTokenException("Page token does not belong to this listing")

        return start_key

    def _pending_requests(self, user_id: str) -> list[PendingRequest]:
        query: dict[str, Any] = {
            "KeyConditionExpression": Key("PK").eq(user_partition(user_id))
            & Key("SK").begins_with("PENDING#")
        }
        items: list[Mapping[str, Any]] = []

        while True:
            response = self._table.query(**query)
            items.extend(response["Items"])
            if "LastEvaluatedKey" not in response:
                break
            query["ExclusiveStartKey"] = response["LastEvaluatedKey"]

        pending = [PendingRequest.from_item(item) for item in items]
        return sorted(pending, key=lambda request: request.requested_at, reverse=True)


def get_scheduling_service(app_configuration: AppConfiguration) -> SchedulingService:
    sync_configuration = app_configuration.sync

    return DynamoDbSchedulingService(
        boto3.resource("dynamodb").Table(sync_configuration.table_name),
        boto3.client("sqs"),
        sync_configuration.fallback_to_main_queue_url,
    )
```

In `src/config/configuration.py`, import `SyncConfiguration`, add `sync: SyncConfiguration` to `AppConfiguration`,
and parse it:

```python
class AppConfiguration(BaseModel):
    cognito: AwsCognitoConfiguration
    http: HttpConfiguration
    video_downloader: VideoDownloaderConfiguration
    sync: SyncConfiguration

    @classmethod
    def parse(cls, config_tree: ConfigTree) -> "AppConfiguration":
        return AppConfiguration(
            cognito=AwsCognitoConfiguration.parse(config_tree),
            http=HttpConfiguration.parse(config_tree),
            video_downloader=VideoDownloaderConfiguration.parse(config_tree),
            sync=SyncConfiguration.parse(config_tree),
        )
```

In `src/dev/run.py`, import `SyncConfiguration` and pass `sync=SyncConfiguration.parse(config_tree)` to the
`AppConfiguration(...)` call (local runs now need `SCHEDULED_VIDEOS_TABLE_NAME` and `FALLBACK_TO_MAIN_QUEUE_URL`).

- [ ] **Step 5: Run the tests to verify they pass**

Run: `.venv/bin/pytest -q`
Expected: all pass.

- [ ] **Step 6: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src tests
git commit -m "Implement fallback scheduling: queue requests to the main API and list videos by role.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Schedule routes and app wiring

**Files:**
- Modify: `src/web/routers/schedule_router.py`
- Modify: `src/web/handlers/exception_handlers.py`
- Modify: `src/main.py`
- Modify: `tests/test_main.py`
- Create: `tests/web/test_schedule_router.py`

**Interfaces:**
- Consumes: `SchedulingService`, `ScheduleListing`, exceptions (Task 6); `authenticated_user_dependency` (Task 2).
- Produces:
  `schedule_router(scheduling_service: SchedulingService, authenticated_user: Callable[..., User]) -> APIRouter`
  with `POST /schedule` → `202 {"requestId": ...}` and `GET /schedule?status=&pageToken=` → `ScheduleListing` JSON.

- [ ] **Step 1: Write the failing tests**

`tests/web/test_schedule_router.py`:

```python
import unittest

from fastapi import FastAPI
from fastapi.testclient import TestClient

from src.services.exceptions import (
    InvalidPageTokenException,
    InvalidUrlException,
    ServiceUnavailableException,
)
from src.services.models.scheduled_video import (
    PendingRequest,
    ScheduleListing,
    VideoSummary,
)
from src.services.models.user import User
from src.services.scheduling_service import SchedulingService
from src.web.handlers.exception_handlers import register_exception_handlers
from src.web.routers.schedule_router import schedule_router

USER = User(id="user-1", email="u1@ruchij.com", first_name="U", last_name="One")


class FakeSchedulingService(SchedulingService):
    def __init__(self):
        self.error: Exception | None = None
        self.calls: list[tuple] = []

    def schedule(self, url: str, user: User) -> str:
        self.calls.append(("schedule", url, user.id))
        if self.error is not None:
            raise self.error
        return "request-1"

    def list_schedules(self, user, status, page_token) -> ScheduleListing:
        self.calls.append(("list", user.id, status, page_token))
        if self.error is not None:
            raise self.error
        return ScheduleListing(
            videos=[
                VideoSummary(
                    video_id="v1",
                    url="https://www.youtube.com/watch?v=abc",
                    video_site="YouTube",
                    title="Sample",
                    duration_ms=1000,
                    size_bytes=2000,
                    status="Queued",
                    scheduled_at="2026-09-26T07:00:00.000Z",
                )
            ],
            pending=[
                PendingRequest(
                    request_id="request-1",
                    url="https://www.youtube.com/watch?v=def",
                    requested_at="2026-09-26T08:00:00.000Z",
                    status="Pending",
                )
            ],
            next_page_token="token-2",
        )


class TestScheduleRouter(unittest.TestCase):
    def setUp(self):
        self.service = FakeSchedulingService()
        app = FastAPI()
        app.include_router(schedule_router(self.service, lambda: USER))
        register_exception_handlers(app)
        self.client = TestClient(app)

    def test_post_schedule_returns_202_with_the_request_id(self):
        response = self.client.post("/schedule", json={"url": "https://www.youtube.com/watch?v=abc"})

        self.assertEqual(response.status_code, 202)
        self.assertEqual(response.json(), {"requestId": "request-1"})
        self.assertEqual(
            self.service.calls, [("schedule", "https://www.youtube.com/watch?v=abc", "user-1")]
        )

    def test_invalid_url_returns_400(self):
        self.service.error = InvalidUrlException('"x" is not an absolute http(s) URL')

        response = self.client.post("/schedule", json={"url": "x"})

        self.assertEqual(response.status_code, 400)

    def test_queue_failure_returns_503(self):
        self.service.error = ServiceUnavailableException("Unable to queue the schedule request")

        response = self.client.post("/schedule", json={"url": "https://example.com/v"})

        self.assertEqual(response.status_code, 503)

    def test_get_schedule_returns_camel_case_listing(self):
        response = self.client.get("/schedule", params={"status": "Queued", "pageToken": "t1"})

        self.assertEqual(response.status_code, 200)
        body = response.json()
        self.assertEqual(body["videos"][0]["videoId"], "v1")
        self.assertEqual(body["videos"][0]["durationMs"], 1000)
        self.assertEqual(body["pending"][0]["requestId"], "request-1")
        self.assertEqual(body["nextPageToken"], "token-2")
        self.assertEqual(self.service.calls, [("list", "user-1", "Queued", "t1")])

    def test_invalid_page_token_returns_400(self):
        self.service.error = InvalidPageTokenException("Malformed page token")

        response = self.client.get("/schedule", params={"pageToken": "garbage"})

        self.assertEqual(response.status_code, 400)
```

In `tests/test_main.py`, extend the expected route set with `"/schedule"`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `.venv/bin/pytest tests/web/test_schedule_router.py tests/test_main.py -q`
Expected: FAIL — `schedule_router()` takes 1 positional argument, and `/schedule` is missing.

- [ ] **Step 3: Implement**

Replace `src/web/routers/schedule_router.py`:

```python
from collections.abc import Callable

from fastapi import APIRouter, Depends
from pydantic import BaseModel

from src.services.models.scheduled_video import ScheduleListing
from src.services.models.user import User
from src.services.scheduling_service import SchedulingService


class ScheduleVideoRequest(BaseModel):
    url: str


class ScheduleVideoResponse(BaseModel):
    requestId: str


def schedule_router(
    scheduling_service: SchedulingService, authenticated_user: Callable[..., User]
) -> APIRouter:
    router = APIRouter(prefix="/schedule")

    @router.post("", status_code=202, response_model=ScheduleVideoResponse)
    def schedule_video(
        schedule_video_request: ScheduleVideoRequest,
        user: User = Depends(authenticated_user),
    ):
        request_id = scheduling_service.schedule(schedule_video_request.url, user)
        return ScheduleVideoResponse(requestId=request_id)

    @router.get("", response_model=ScheduleListing)
    def list_schedules(
        status: str | None = None,
        pageToken: str | None = None,
        user: User = Depends(authenticated_user),
    ):
        return scheduling_service.list_schedules(user, status, pageToken)

    return router
```

In `src/web/handlers/exception_handlers.py`, import the three new exceptions and add inside
`register_exception_handlers`:

```python
    @app.exception_handler(InvalidUrlException)
    async def handle_invalid_url(request: Request, exc: InvalidUrlException):
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(InvalidPageTokenException)
    async def handle_invalid_page_token(
        request: Request, exc: InvalidPageTokenException
    ):
        return JSONResponse(status_code=400, content={"detail": str(exc)})

    @app.exception_handler(ServiceUnavailableException)
    async def handle_service_unavailable(
        request: Request, exc: ServiceUnavailableException
    ):
        return JSONResponse(status_code=503, content={"detail": str(exc)})
```

In `src/main.py`, import `get_scheduling_service` and change the schedule router line to:

```python
    scheduling_service = get_scheduling_service(app_configuration)
    ...
    app.include_router(schedule_router(scheduling_service, authenticated_user))
```

(place `scheduling_service = ...` next to the other service constructions).

- [ ] **Step 4: Run the tests to verify they pass**

Run: `.venv/bin/pytest -q`
Expected: all pass.

- [ ] **Step 5: Lint, type-check, commit**

```bash
.venv/bin/ruff format . && .venv/bin/ruff check . && .venv/bin/mypy . && .venv/bin/pytest -q
git add src tests
git commit -m "Expose POST and GET /schedule on the fallback API.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Infrastructure (SAM template)

**Files:**
- Modify: `template.yaml`
- Modify: `requirements.dev.txt` (add `cfn-lint==1.57.0`)

**Interfaces:**
- Consumes: handler paths `handler.handler` (existing) and `sync_handler.handler` (Task 5); environment variables
  `SCHEDULED_VIDEOS_TABLE_NAME` and `FALLBACK_TO_MAIN_QUEUE_URL` (Tasks 5–6).
- Produces (stack outputs used by phase 2): `MainToFallbackQueueUrl`, `FallbackToMainQueueUrl`,
  `ScheduledVideosTableName`, `MainSideSyncUserName`.

- [ ] **Step 1: Add cfn-lint and confirm the current template lints**

Add `cfn-lint==1.57.0` to `requirements.dev.txt` (keep the list alphabetical), then:

Run: `.venv/bin/pip install -q -r requirements.dev.txt && .venv/bin/cfn-lint template.yaml`
Expected: exits 0 (or only warnings); note any pre-existing findings so they are not confused with new ones.

- [ ] **Step 2: Replace `template.yaml`**

```yaml
AWSTemplateFormatVersion: '2010-09-09'
Transform: AWS::Serverless-2016-10-31
Description: Fallback Video API - API to handle fallback requests when the video API is unavailable

Parameters:
  Stage:
    Type: String
    Description: Deployment stage
  DomainName:
    Type: String
    Description: Custom domain name (e.g. fallback-api.video.ruchij.com)
  HostedZoneId:
    Type: AWS::Route53::HostedZone::Id
    Description: Route 53 Hosted Zone ID
    Default: Z1LYXU4HVSANEL
  VideoDownloaderApiUrl:
    Type: String
    Description: URL of the video downloader API
  AlarmEmail:
    Type: String
    Description: Optional e-mail address notified when a sync dead-letter queue receives messages
    Default: ""

Conditions:
  HasAlarmEmail: !Not [!Equals [!Ref AlarmEmail, ""]]

Globals:
  Function:
    Timeout: 30
    Runtime: python3.14
    Architectures:
      - x86_64
    Environment:
      Variables:
        STAGE: !Ref Stage
        SCHEDULED_VIDEOS_TABLE_NAME: !Ref ScheduledVideosTable
        FALLBACK_TO_MAIN_QUEUE_URL: !Ref FallbackToMainQueue

Resources:
  ApiFunction:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: !Sub "${Stage}-fallback-api"
      Handler: handler.handler
      CodeUri: .
      MemorySize: 256
      LoggingConfig:
        LogGroup: !Ref ApiFunctionLogGroup
      Environment:
        Variables:
          AWS_COGNITO_USER_POOL_ID: !Ref UserPool
          AWS_COGNITO_CLIENT_ID: !Ref UserPoolClient
          VIDEO_DOWNLOADER_API_URL: !Ref VideoDownloaderApiUrl
      Policies:
        - DynamoDBCrudPolicy:
            TableName: !Ref ScheduledVideosTable
        - SQSSendMessagePolicy:
            QueueName: !GetAtt FallbackToMainQueue.QueueName
        - Statement:
            - Effect: Allow
              Action:
                - cognito-idp:AdminCreateUser
                - cognito-idp:AdminSetUserPassword
                - cognito-idp:AdminDeleteUser
                - cognito-idp:DescribeUserPoolClient
              Resource: !GetAtt UserPool.Arn
      Events:
        RootPath:
          Type: Api
          Properties:
            Path: /
            Method: ANY
        ProxyPath:
          Type: Api
          Properties:
            Path: /{proxy+}
            Method: ANY

  SyncFunction:
    Type: AWS::Serverless::Function
    Properties:
      FunctionName: !Sub "${Stage}-fallback-api-sync"
      Handler: sync_handler.handler
      CodeUri: .
      MemorySize: 256
      LoggingConfig:
        LogGroup: !Ref SyncFunctionLogGroup
      Policies:
        - DynamoDBCrudPolicy:
            TableName: !Ref ScheduledVideosTable
      Events:
        MainToFallback:
          Type: SQS
          Properties:
            Queue: !GetAtt MainToFallbackQueue.Arn
            BatchSize: 10
            FunctionResponseTypes:
              - ReportBatchItemFailures

  ApiFunctionLogGroup:
    Type: AWS::Logs::LogGroup
    Properties:
      LogGroupName: !Sub "/fallback-api/${Stage}/api"
      RetentionInDays: 14

  SyncFunctionLogGroup:
    Type: AWS::Logs::LogGroup
    Properties:
      LogGroupName: !Sub "/fallback-api/${Stage}/sync"
      RetentionInDays: 14

  ScheduledVideosTable:
    Type: AWS::DynamoDB::Table
    Properties:
      TableName: !Sub "${Stage}-fallback-scheduled-videos"
      BillingMode: PAY_PER_REQUEST
      AttributeDefinitions:
        - AttributeName: PK
          AttributeType: S
        - AttributeName: SK
          AttributeType: S
        - AttributeName: GSI1PK
          AttributeType: S
        - AttributeName: GSI1SK
          AttributeType: S
      KeySchema:
        - AttributeName: PK
          KeyType: HASH
        - AttributeName: SK
          KeyType: RANGE
      GlobalSecondaryIndexes:
        - IndexName: GSI1
          KeySchema:
            - AttributeName: GSI1PK
              KeyType: HASH
            - AttributeName: GSI1SK
              KeyType: RANGE
          Projection:
            ProjectionType: ALL
      TimeToLiveSpecification:
        AttributeName: ttl
        Enabled: true

  MainToFallbackDeadLetterQueue:
    Type: AWS::SQS::Queue
    Properties:
      MessageRetentionPeriod: 1209600

  MainToFallbackQueue:
    Type: AWS::SQS::Queue
    Properties:
      MessageRetentionPeriod: 1209600
      VisibilityTimeout: 180
      RedrivePolicy:
        deadLetterTargetArn: !GetAtt MainToFallbackDeadLetterQueue.Arn
        maxReceiveCount: 5

  FallbackToMainDeadLetterQueue:
    Type: AWS::SQS::Queue
    Properties:
      MessageRetentionPeriod: 1209600

  FallbackToMainQueue:
    Type: AWS::SQS::Queue
    Properties:
      MessageRetentionPeriod: 1209600
      VisibilityTimeout: 300
      RedrivePolicy:
        deadLetterTargetArn: !GetAtt FallbackToMainDeadLetterQueue.Arn
        maxReceiveCount: 5

  SyncAlarmTopic:
    Type: AWS::SNS::Topic

  SyncAlarmEmailSubscription:
    Type: AWS::SNS::Subscription
    Condition: HasAlarmEmail
    Properties:
      TopicArn: !Ref SyncAlarmTopic
      Protocol: email
      Endpoint: !Ref AlarmEmail

  MainToFallbackDeadLetterAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmDescription: Messages from the main API could not be applied to DynamoDB
      Namespace: AWS/SQS
      MetricName: ApproximateNumberOfMessagesVisible
      Dimensions:
        - Name: QueueName
          Value: !GetAtt MainToFallbackDeadLetterQueue.QueueName
      Statistic: Maximum
      Period: 300
      EvaluationPeriods: 1
      Threshold: 0
      ComparisonOperator: GreaterThanThreshold
      TreatMissingData: notBreaching
      AlarmActions:
        - !Ref SyncAlarmTopic

  FallbackToMainDeadLetterAlarm:
    Type: AWS::CloudWatch::Alarm
    Properties:
      AlarmDescription: Schedule requests could not be processed by the main API
      Namespace: AWS/SQS
      MetricName: ApproximateNumberOfMessagesVisible
      Dimensions:
        - Name: QueueName
          Value: !GetAtt FallbackToMainDeadLetterQueue.QueueName
      Statistic: Maximum
      Period: 300
      EvaluationPeriods: 1
      Threshold: 0
      ComparisonOperator: GreaterThanThreshold
      TreatMissingData: notBreaching
      AlarmActions:
        - !Ref SyncAlarmTopic

  MainSideSyncUser:
    Type: AWS::IAM::User
    Properties:
      Policies:
        - PolicyName: fallback-sync
          PolicyDocument:
            Version: '2012-10-17'
            Statement:
              - Effect: Allow
                Action: sqs:SendMessage
                Resource: !GetAtt MainToFallbackQueue.Arn
              - Effect: Allow
                Action:
                  - sqs:ReceiveMessage
                  - sqs:DeleteMessage
                  - sqs:ChangeMessageVisibility
                Resource: !GetAtt FallbackToMainQueue.Arn
              - Effect: Allow
                Action: dynamodb:Scan
                Resource: !GetAtt ScheduledVideosTable.Arn

  UserPool:
    Type: AWS::Cognito::UserPool
    Properties:
      UserPoolName: !Sub "${Stage}-fallback-api"
      Schema:
        - AttributeDataType: String
          Mutable: false
          Name: user_id
        - AttributeDataType: String
          Mutable: true
          Name: role

  UserPoolClient:
    Type: AWS::Cognito::UserPoolClient
    Properties:
      UserPoolId: !Ref UserPool
      ClientName: !Sub "${Stage}-fallback-api-client"
      GenerateSecret: true
      ExplicitAuthFlows:
        - ALLOW_USER_PASSWORD_AUTH
        - ALLOW_REFRESH_TOKEN_AUTH
      WriteAttributes:
        - email
        - given_name
        - family_name

  Certificate:
    Type: AWS::CertificateManager::Certificate
    Properties:
      DomainName: !Ref DomainName
      ValidationMethod: DNS
      DomainValidationOptions:
        - DomainName: !Ref DomainName
          HostedZoneId: !Ref HostedZoneId

  ApiDomainName:
    Type: AWS::ApiGateway::DomainName
    Properties:
      DomainName: !Ref DomainName
      RegionalCertificateArn: !Ref Certificate
      EndpointConfiguration:
        Types:
          - REGIONAL

  ApiBasePathMapping:
    Type: AWS::ApiGateway::BasePathMapping
    DependsOn: ServerlessRestApiProdStage
    Properties:
      DomainName: !Ref ApiDomainName
      RestApiId: !Ref ServerlessRestApi
      Stage: Prod

  DnsRecord:
    Type: AWS::Route53::RecordSet
    Properties:
      HostedZoneId: !Ref HostedZoneId
      Name: !Ref DomainName
      Type: A
      AliasTarget:
        DNSName: !GetAtt ApiDomainName.RegionalDomainName
        HostedZoneId: !GetAtt ApiDomainName.RegionalHostedZoneId

Outputs:
  UserPoolId:
    Description: Cognito User Pool ID
    Value: !Ref UserPool
  UserPoolClientId:
    Description: Cognito User Pool Client ID
    Value: !Ref UserPoolClient
  CustomDomainUrl:
    Description: Custom domain URL
    Value: !Sub "https://${DomainName}/"
  ScheduledVideosTableName:
    Description: DynamoDB table holding the scheduled video copy (FALLBACK_SYNC_TABLE_NAME on the main side)
    Value: !Ref ScheduledVideosTable
  MainToFallbackQueueUrl:
    Description: Queue the main API sends sync messages to (FALLBACK_SYNC_MAIN_TO_FALLBACK_QUEUE_URL)
    Value: !Ref MainToFallbackQueue
  FallbackToMainQueueUrl:
    Description: Queue the main API polls for schedule requests (FALLBACK_SYNC_FALLBACK_TO_MAIN_QUEUE_URL)
    Value: !Ref FallbackToMainQueue
  MainSideSyncUserName:
    Description: IAM user for the main API; create its access keys by hand
    Value: !Ref MainSideSyncUser
```

- [ ] **Step 3: Lint the template**

Run: `.venv/bin/cfn-lint template.yaml`
Expected: exits 0 with no new errors beyond those noted in Step 1. Fix any new finding before continuing.

- [ ] **Step 4: Commit**

```bash
git add template.yaml requirements.dev.txt
git commit -m "Add the fallback sync infrastructure: DynamoDB table, SQS queues, SyncFunction and alarms.

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Final verification and deployment check (manual steps for the user)

**Files:** none.

- [ ] **Step 1: Full local verification**

Run:

```bash
.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/mypy .
.venv/bin/pytest -q && .venv/bin/cfn-lint template.yaml
```

Expected: everything passes.

- [ ] **Step 2: Hand over the deployment check to the user (do not run it yourself)**

Tell the user to create a change set for staging *without executing it* and check the `UserPool` row:

```bash
sam build && sam deploy --no-execute-changeset
```

If `UserPool` shows `Replacement: True`, deploying would recreate the pool and existing fallback users would have to
sign up again. The user decides whether to proceed. After deploying, the user creates access keys for the
`MainSideSyncUserName` output for phase 2.
