#!/usr/bin/env bash
set -euo pipefail

MODE="${1:-readiness}"

# Check 1: OpenVPN process is alive
if ! pgrep -x openvpn > /dev/null 2>&1; then
  echo "FAIL: openvpn process not running"
  exit 1
fi

# Check 2: tun0 interface exists and is UP
if ! ip link show tun0 2>/dev/null | grep -q "state UP\|state UNKNOWN"; then
  echo "FAIL: tun0 interface not up"
  exit 1
fi

# Liveness: process + interface is sufficient
# Avoid killing the pod over a transient network blip from an external endpoint.
if [ "$MODE" = "liveness" ]; then
  echo "OK: openvpn running, tun0 up"
  exit 0
fi

# Readiness: verify end-to-end VPN connectivity through the proxy.
# Two fallback endpoints with 5s timeout each (10s worst case, within K8s 15s timeout).
for endpoint in https://ifconfig.me https://icanhazip.com; do
  if curl -sf --proxy http://127.0.0.1:8888 --max-time 5 -o /dev/null "$endpoint" 2>/dev/null; then
    echo "OK: VPN connectivity verified via ${endpoint}"
    exit 0
  fi
done

echo "FAIL: VPN connectivity check failed"
exit 1
