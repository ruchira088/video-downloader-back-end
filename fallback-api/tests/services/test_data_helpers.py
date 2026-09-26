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
