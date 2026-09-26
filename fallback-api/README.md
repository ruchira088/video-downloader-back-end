# Fallback API

An AWS SAM stack that serves users while the main video-downloader API is unavailable. Users sign up with their
main-API credentials, list their scheduled videos, and schedule new ones. Its DynamoDB table holds a copy of the
scheduled videos, kept in sync with the main API's database over two SQS queues (see
`docs/superpowers/specs/2026-09-26-fallback-sync-design.md`).

- `ApiFunction` (`handler.py`): the FastAPI app behind API Gateway.
- `SyncFunction` (`sync_handler.py`): applies the main API's sync messages to DynamoDB.
- `contract/`: JSON fixtures shared with the main API's tests. Change them only together with the Scala side.

## Development

```bash
python3.14 -m venv .venv
.venv/bin/pip install -r requirements.dev.txt

.venv/bin/ruff check . && .venv/bin/ruff format --check . && .venv/bin/mypy .
.venv/bin/pytest -q
.venv/bin/cfn-lint template.yaml

.venv/bin/python -m src.dev.run   # the API against a local Cognito emulator (needs Docker and the sync variables)
```

## Configuration

`application.conf` is read at startup, and every setting can be overridden with an environment variable:

| Variable | Description |
|---|---|
| `AWS_COGNITO_USER_POOL_ID` | User pool that issues access tokens. Its region is taken from the id's prefix. |
| `AWS_COGNITO_CLIENT_ID` | App client that access tokens must have been issued to |
| `AWS_COGNITO_URL` | Cognito endpoint override, for a local emulator only. The JWKS is then fetched from it. |
| `AWS_COGNITO_ISSUER` | Expected token issuer, for a local emulator only. Defaults to the user pool's AWS issuer. |
| `VIDEO_DOWNLOADER_API_URL` | Main API, which sign-up checks credentials against |
| `SCHEDULED_VIDEOS_TABLE_NAME` | DynamoDB table holding the scheduled video copy |
| `FALLBACK_TO_MAIN_QUEUE_URL` | SQS queue that schedule requests are sent to |

`template.yaml` sets all of these for the deployed functions, except the two emulator overrides.

`AWS_COGNITO_URL` and `AWS_COGNITO_ISSUER` must never be set in AWS. Each also changes where the token signing keys
are fetched from (`<AWS_COGNITO_URL>/<userPoolId>/.well-known/jwks.json`, or else
`<AWS_COGNITO_ISSUER>/.well-known/jwks.json`), so pointing either at another host makes the API trust any token that
host's keys signed.

Bearer tokens are verified locally before they are used: the signature against the user pool's JWKS
(`https://cognito-idp.<region>.amazonaws.com/<userPoolId>/.well-known/jwks.json`, cached), then the issuer, the
`client_id` and `token_use = access` claims, and the expiry. Cognito's GetUser is still called afterwards, so a revoked
token is rejected too. A JWKS that can't be fetched or read, or a Cognito internal error, is answered with a 503, and
Cognito throttling with a 429, rather than a 401.

## Deployment

```bash
sam build
sam deploy                          # staging
sam deploy --config-env prod        # production
```

- **Alarm e-mail.** Set the `AlarmEmail` parameter in `samconfig.toml`, by adding `AlarmEmail=you@example.com` to
  `parameter_overrides`. AWS then e-mails that address to confirm the SNS subscription; no dead-letter alarm is
  delivered until the link in that e-mail is followed.
- **Main-side access.** Create access keys for the `MainSideSyncUser` output by hand, and give them to the main API
  (see the repository README's fallback sync settings).
- **The user pool is retained.** It has deletion protection, and CloudFormation keeps it if the stack is deleted or
  the pool would be replaced.

## Limitations

- **Passwords shorter than 6 characters can't sign up.** Cognito's minimum password length is 6, and sign-up copies
  the user's main-API password into the user pool. A user with a shorter password must change it on the main API
  first.
- A user's role is copied at sign-up and not refreshed afterwards.
- `GET /schedule` lists at most the 100 newest pending requests.
