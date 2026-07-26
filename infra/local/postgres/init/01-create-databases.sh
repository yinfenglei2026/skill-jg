#!/bin/bash
set -euo pipefail

psql --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres \
  --set governance_db_username="$GOVERNANCE_DB_USERNAME" \
  --set governance_db_password="$GOVERNANCE_DB_PASSWORD" \
  --set keycloak_db_username="$KEYCLOAK_DB_USERNAME" \
  --set keycloak_db_password="$KEYCLOAK_DB_PASSWORD" <<'EOSQL'
CREATE ROLE :"governance_db_username" LOGIN PASSWORD :'governance_db_password';
CREATE DATABASE governance OWNER :"governance_db_username";
CREATE ROLE :"keycloak_db_username" LOGIN PASSWORD :'keycloak_db_password';
CREATE DATABASE keycloak OWNER :"keycloak_db_username";
EOSQL
