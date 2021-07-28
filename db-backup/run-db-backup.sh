#!/usr/bin/env bash

: "${GIT_BRANCH:? Variable NOT defined}"
: "${GIT_COMMIT:? Variable NOT defined}"
: "${DATABASE_URL:? Variable NOT defined}"
: "${DATABASE_USER:? Variable NOT defined}"
: "${DATABASE_PASSWORD:? Variable NOT defined}"

backup_sql_file=/opt/backup.sql
backup_zip_file="/opt/$(date +%Y-%m-%d-%H-%M-%S)-$GIT_COMMIT-backup.zip"
database_url="postgresql://$DATABASE_USER:$DATABASE_PASSWORD@$DATABASE_URL"

echo "Started creating backup..."

pg_dump --dbname="$database_url" -f $backup_sql_file
zip -j "$backup_zip_file" "$backup_sql_file"

echo "Backup completed"

echo "Saving to S3..."

aws s3 cp "$backup_zip_file" "s3://backups.video-downloader.ruchij.com/$GIT_BRANCH/"

echo "Save completed"