#!/bin/bash
# Run Maven through auth proxy for environments with authenticated proxy

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
PROJECT_DIR="$(dirname "$SCRIPT_DIR")"

# Start auth proxy in background
echo "Starting auth proxy..."
python3 "$SCRIPT_DIR/auth-proxy.py" &
PROXY_PID=$!

# Wait for proxy to start
sleep 1

# Check if proxy is running
if ! kill -0 $PROXY_PID 2>/dev/null; then
    echo "Failed to start auth proxy"
    exit 1
fi

cleanup() {
    echo "Stopping auth proxy..."
    kill $PROXY_PID 2>/dev/null
}
trap cleanup EXIT

# Run Maven with local proxy
cd "$PROJECT_DIR"
MAVEN_OPTS="-Dhttp.proxyHost=127.0.0.1 -Dhttp.proxyPort=3128 -Dhttps.proxyHost=127.0.0.1 -Dhttps.proxyPort=3128" \
mvn "$@"
