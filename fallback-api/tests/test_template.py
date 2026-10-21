import unittest
from pathlib import Path
from typing import Any

from cfnlint.decode import cfn_yaml

TEMPLATE_PATH = Path(__file__).resolve().parent.parent / "template.yaml"


def _statements(function: dict[str, Any]) -> list[dict[str, Any]]:
    return [
        statement
        for policy in function["Properties"]["Policies"]
        for statement in policy.get("Statement", [])
    ]


def _actions(statement: dict[str, Any]) -> set[str]:
    actions = statement["Action"]
    return {actions} if isinstance(actions, str) else set(actions)


class TestTemplate(unittest.TestCase):
    def setUp(self):
        self.template = cfn_yaml.load(str(TEMPLATE_PATH))
        self.resources = self.template["Resources"]

    def test_the_api_function_may_only_query_and_put_items_in_the_table(self):
        api_function = self.resources["ApiFunction"]
        policy_templates = {
            name for policy in api_function["Properties"]["Policies"] for name in policy
        }

        self.assertNotIn("DynamoDBCrudPolicy", policy_templates)
        self.assertNotIn("DynamoDBReadPolicy", policy_templates)
        self.assertNotIn("DynamoDBWritePolicy", policy_templates)

        dynamodb_actions = {
            action
            for statement in _statements(api_function)
            for action in _actions(statement)
            if action.startswith("dynamodb:")
        }
        # DynamoDbSchedulingService only queries (the table and GSI1) and puts pending items.
        self.assertEqual(dynamodb_actions, {"dynamodb:Query", "dynamodb:PutItem"})

        for statement in _statements(api_function):
            if "dynamodb:PutItem" in _actions(statement):
                # Items are only put in the table itself, never through an index.
                self.assertEqual(
                    statement["Resource"],
                    {"Fn::GetAtt": ["ScheduledVideosTable", "Arn"]},
                )

    def test_the_sync_function_may_only_look_up_and_delete_fallback_users(self):
        # CognitoUserRevoker applies the main API's UserRevocation messages.
        sync_function = self.resources["SyncFunction"]
        environment = sync_function["Properties"]["Environment"]["Variables"]

        self.assertEqual(environment["AWS_COGNITO_USER_POOL_ID"], {"Ref": "UserPool"})

        cognito_statements = [
            statement
            for statement in _statements(sync_function)
            if any(action.startswith("cognito-idp:") for action in _actions(statement))
        ]
        self.assertEqual(len(cognito_statements), 1)
        self.assertEqual(
            _actions(cognito_statements[0]),
            {"cognito-idp:AdminGetUser", "cognito-idp:AdminDeleteUser"},
        )
        self.assertEqual(
            cognito_statements[0]["Resource"], {"Fn::GetAtt": ["UserPool", "Arn"]}
        )

    def _method_settings(self) -> dict[tuple[str, str], dict[str, Any]]:
        settings = self.template["Globals"]["Api"]["MethodSettings"]
        return {
            (setting["ResourcePath"], setting["HttpMethod"]): setting
            for setting in settings
        }

    def test_every_api_method_is_throttled(self):
        stage_wide = self._method_settings()[("/*", "*")]

        self.assertGreater(stage_wide["ThrottlingRateLimit"], 0)
        self.assertGreater(stage_wide["ThrottlingBurstLimit"], 0)

    def test_sign_up_has_its_own_stricter_throttle(self):
        settings = self._method_settings()
        stage_wide = settings[("/*", "*")]
        # MethodSettings write a resource path with each of its "/" as "~1", after a leading "/"
        sign_up = settings[("/~1user", "POST")]

        self.assertLess(
            sign_up["ThrottlingRateLimit"], stage_wide["ThrottlingRateLimit"]
        )
        self.assertLess(
            sign_up["ThrottlingBurstLimit"], stage_wide["ThrottlingBurstLimit"]
        )

        # The throttle applies to the /user resource's POST method, so that method must exist
        # rather than being served by the /{proxy+} resource.
        events = self.resources["ApiFunction"]["Properties"]["Events"].values()
        self.assertIn(
            {"Path": "/user", "Method": "POST"},
            [event["Properties"] for event in events if event["Type"] == "Api"],
        )
