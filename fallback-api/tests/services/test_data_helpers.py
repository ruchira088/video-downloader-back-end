from datetime import datetime, UTC

from src.services.models.user import User

sample_user: User = User(
    id="my-user-id",
    created_at=datetime.now(UTC),
    email="me@ruchij.com",
    first_name="John",
    last_name="Doe",
)

sample_password: str = "cam2QGH8eht!vbz1nrh"
