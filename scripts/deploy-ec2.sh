#!/usr/bin/env bash
set -Eeuo pipefail

ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
COMPOSE=(docker compose --profile ec2 --env-file "$ROOT_DIR/.env" -f "$ROOT_DIR/docker-compose.yml")

if [[ ! -f "$ROOT_DIR/.env" ]]; then
  printf '%s\n' "Missing $ROOT_DIR/.env; copy .env.example and set private values." >&2
  exit 1
fi

cd "$ROOT_DIR"
"${COMPOSE[@]}" config >/dev/null
JAVA_TOOL_OPTIONS=-Xmx256m ./mvnw -DskipTests package
"${COMPOSE[@]}" build
"${COMPOSE[@]}" up -d --remove-orphans
"${COMPOSE[@]}" ps
printf '%s\n' 'Deployment started. Run docs/DEPLOY_EC2.md smoke test before traffic.'
