#!/bin/bash
# Generate a test JWT token via the /dev/token endpoint.
# Usage: ./generate-token.sh [userId] [tenantId]
#
# Prerequisites: The app must be running with usql.auth.mock-enabled=true
# (the default in development mode).

USER_ID=${1:-alice}
TENANT_ID=${2:-acme}
BASE_URL=${BASE_URL:-http://localhost:8080}

echo "Requesting token for userId=${USER_ID} tenantId=${TENANT_ID}"
TOKEN=$(curl -s "${BASE_URL}/dev/token?userId=${USER_ID}&tenantId=${TENANT_ID}")
echo "Token: ${TOKEN}"
echo ""
echo "Export for load test:"
echo "  export AUTH_TOKEN='${TOKEN}'"
echo "  export BASE_URL='${BASE_URL}'"
echo "  k6 run k6/load-test.js"
