#!/usr/bin/env bash

set -e

: "${S3_DB_BACKUP_FILE:? Variable NOT defined}"
: "${DATABASE_URL:? Variable NOT defined}"
: "${DATABASE_USER:? Variable NOT defined}"
: "${DATABASE_PASSWORD:? Variable NOT defined}"

echo "Downloading backup data from AWS S3 file: $S3_DB_BACKUP_FILE"

aws s3 cp "$S3_DB_BACKUP_FILE" backup.zip

echo "Download completed from S3"

unzip backup.zip -d backup_contents

database_url="postgresql://$DATABASE_USER:$DATABASE_PASSWORD@$DATABASE_URL"

echo "Applying backup SQL data to database"

psql "$database_url" -c "DROP SCHEMA public CASCADE; CREATE SCHEMA public;"

psql "$database_url" < backup_contents/backup.sql

echo "Database restore completed"