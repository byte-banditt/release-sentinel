#!/usr/bin/env bash
set -euo pipefail

# Manual only. Run from agent/ after configuring AWS credentials and region.
# Set an AWS Budget alert before this script: Billing > Budgets > Create budget,
# monthly cost budget, alert at 50% and 80%, with email recipients.

: "${AWS_REGION:?Set AWS_REGION}"
: "${BEDROCK_MODEL_ID:?Set BEDROCK_MODEL_ID after confirming model access in this region}"

python3 -m venv .venv
. .venv/bin/activate
python -m pip install --upgrade pip
python -m pip install -r requirements.txt

agentcore configure --entrypoint main.py --name release-sentinel-agent \
  --runtime PYTHON_3_12 --disable-memory --region "$AWS_REGION" --non-interactive
agentcore deploy
agentcore invoke '{"eventId":"manual","repo":"example/repo","commitSha":"sha","changedFiles":[],"logExcerpt":"build failed"}'
