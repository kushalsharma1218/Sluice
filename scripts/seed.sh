#!/usr/bin/env bash
# Seeds an org -> team tree, credits it, sets a budget, and prints a working
# virtual key. This is the v1 substitute for a web UI.
set -euo pipefail

SLUICE_URL="${SLUICE_URL:-http://localhost:8080}"
ADMIN_TOKEN="${SLUICE_ADMIN_TOKEN:-dev-admin-token}"
ORG_NAME="${ORG_NAME:-acme}"
TEAM_NAME="${TEAM_NAME:-platform}"
CREDIT="${CREDIT:-25.00}"
BUDGET="${BUDGET:-5.00}"
PERIOD="${PERIOD:-MONTHLY}"

api() {
  local method="$1" path="$2" body="${3:-}"
  if [[ -n "$body" ]]; then
    curl -fsS -X "$method" "$SLUICE_URL$path" \
      -H "authorization: Bearer $ADMIN_TOKEN" \
      -H 'content-type: application/json' \
      -d "$body"
  else
    curl -fsS -X "$method" "$SLUICE_URL$path" -H "authorization: Bearer $ADMIN_TOKEN"
  fi
}

# jq is nice but not required; fall back to a narrow grep.
field() {
  if command -v jq >/dev/null 2>&1; then
    jq -r ".$1"
  else
    grep -o "\"$1\":\"[^\"]*\"" | head -1 | cut -d'"' -f4
  fi
}

echo "==> waiting for Sluice at $SLUICE_URL"
for _ in $(seq 1 60); do
  if curl -fsS "$SLUICE_URL/actuator/health" >/dev/null 2>&1; then break; fi
  sleep 1
done

echo "==> creating org '$ORG_NAME'"
ORG_ID=$(api POST /admin/accounts "{\"name\":\"$ORG_NAME\",\"type\":\"ORG\",\"currency\":\"USD\"}" | field id)

echo "==> creating team '$TEAM_NAME' under $ORG_ID"
TEAM_ID=$(api POST /admin/accounts \
  "{\"name\":\"$TEAM_NAME\",\"type\":\"TEAM\",\"parentId\":\"$ORG_ID\"}" | field id)

echo "==> crediting \$$CREDIT"
api POST "/admin/accounts/$TEAM_ID/credits" \
  "{\"amount\":\"$CREDIT\",\"reference\":\"seed-$(date +%s)\"}" >/dev/null

echo "==> setting a $PERIOD hard budget of \$$BUDGET"
api PUT "/admin/accounts/$TEAM_ID/budget" \
  "{\"period\":\"$PERIOD\",\"limit\":\"$BUDGET\",\"hardStop\":true}" >/dev/null

echo "==> issuing a virtual key"
KEY=$(api POST "/admin/accounts/$TEAM_ID/keys" '{"name":"seed"}' | field key)

cat <<EOF

  Org  $ORG_ID
  Team $TEAM_ID
  Key  $KEY

  Try it:

    curl $SLUICE_URL/v1/messages \\
      -H "x-api-key: $KEY" \\
      -H "content-type: application/json" \\
      -d '{"model":"claude-opus-5","max_tokens":64,
           "messages":[{"role":"user","content":"Say hello in five words."}]}'

  Then look at the books:

    curl -H "authorization: Bearer $ADMIN_TOKEN" \\
      $SLUICE_URL/admin/accounts/$TEAM_ID/balance
    curl -H "authorization: Bearer $ADMIN_TOKEN" \\
      $SLUICE_URL/admin/accounts/$TEAM_ID/usage

  For the Anthropic SDK, this is the whole change:

    client = Anthropic(base_url="$SLUICE_URL", api_key="$KEY")

EOF
