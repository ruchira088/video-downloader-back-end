import pytest


@pytest.fixture(autouse=True)
def aws_region(monkeypatch):
    monkeypatch.setenv("AWS_DEFAULT_REGION", "ap-southeast-2")
