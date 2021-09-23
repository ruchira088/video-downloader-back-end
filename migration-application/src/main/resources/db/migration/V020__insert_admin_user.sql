INSERT INTO api_user(id, created_at, first_name, last_name, email, role)
    VALUES (
        '648f12b1-b207-4db2-9bef-1e20cd23cdf1',
        CURRENT_TIMESTAMP,
        'Ruchira',
        'Jayasekara',
        'me@ruchij.com',
        'Admin'
   );

INSERT INTO credentials(user_id, last_updated_at, hashed_password)
    VALUES ('648f12b1-b207-4db2-9bef-1e20cd23cdf1', CURRENT_TIMESTAMP, '${adminHashedPassword}');