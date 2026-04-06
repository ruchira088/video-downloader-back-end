#!/usr/bin/env bash
set -euo pipefail

# --- Validate required env / files ---
if [ -z "${OPENVPN_USER:-}" ] || [ -z "${OPENVPN_PASS:-}" ]; then
  echo "ERROR: OPENVPN_USER and OPENVPN_PASS environment variables are required."
  echo "Find them at: https://www.expressvpn.com/setup#manual"
  exit 1
fi

# --- Resolve VPN config file ---
VPN_COUNTRY="${VPN_COUNTRY:-}"
if [ -n "$VPN_COUNTRY" ]; then
  OVPN_FILE="/etc/openvpn/configs/${VPN_COUNTRY}.ovpn"
  if [ ! -f "$OVPN_FILE" ]; then
    echo "ERROR: No config found for country '${VPN_COUNTRY}' at ${OVPN_FILE}"
    echo "Available configs:"
    ls /etc/openvpn/configs/*.ovpn 2>/dev/null | sed 's|.*/||; s|\.ovpn$||' || echo "  (none)"
    exit 1
  fi
else
  # Fallback: pick the first .ovpn file found
  OVPN_FILE=$(find /etc/openvpn/configs -name '*.ovpn' -print -quit 2>/dev/null || true)
  if [ -z "$OVPN_FILE" ]; then
    echo "ERROR: No .ovpn files found in /etc/openvpn/configs/"
    echo "Mount your .ovpn files: -v ./vpn-config:/etc/openvpn/configs:ro"
    exit 1
  fi
fi
echo "Using VPN config: ${OVPN_FILE}"

# --- Create TUN device if missing ---
mkdir -p /dev/net
[ -c /dev/net/tun ] || mknod /dev/net/tun c 10 200

# --- Save Docker network info before VPN overwrites routes ---
DEFAULT_GW=$(ip route | awk '/default/ {print $3}')
DEFAULT_IF=$(ip route | awk '/default/ {print $5}')
DOCKER_SUBNETS=$(ip route | grep "dev ${DEFAULT_IF}" | grep -v default | awk '{print $1}')
echo "Docker gateway: ${DEFAULT_GW} via ${DEFAULT_IF}, subnets: ${DOCKER_SUBNETS}"

# --- Write credentials file ---
echo "$OPENVPN_USER" > /etc/openvpn/credentials.txt
echo "$OPENVPN_PASS" >> /etc/openvpn/credentials.txt
chmod 600 /etc/openvpn/credentials.txt

# --- Start OpenVPN in the background ---
echo "Starting OpenVPN..."
openvpn \
  --config "$OVPN_FILE" \
  --auth-user-pass /etc/openvpn/credentials.txt \
  --auth-nocache \
  --daemon \
  --log /var/log/openvpn.log \
  --writepid /run/openvpn.pid

# --- Wait for tunnel to come up ---
echo "Waiting for VPN tunnel..."
MAX_WAIT=30
WAITED=0
while ! ip link show tun0 > /dev/null 2>&1; do
  sleep 1
  WAITED=$((WAITED + 1))
  if [ "$WAITED" -ge "$MAX_WAIT" ]; then
    echo "ERROR: VPN tunnel did not come up within ${MAX_WAIT}s."
    echo "--- OpenVPN log ---"
    cat /var/log/openvpn.log
    exit 1
  fi
done
echo "VPN tunnel is up (tun0 ready in ${WAITED}s)."

# --- Fix routing: ensure local network traffic goes back via eth0, not VPN ---
# OpenVPN pushes 0.0.0.0/1 and 128.0.0.0/1 via tun0 which captures return
# traffic to Docker's port forwarding. We use policy routing to fix this:
# any packet destined for Docker/local subnets uses a separate routing table.
ip route add default via "${DEFAULT_GW}" dev "${DEFAULT_IF}" table 100
for subnet in ${DOCKER_SUBNETS}; do
  ip rule add from "${subnet}" table 100
  echo "Added policy route for subnet ${subnet}."
done
# Route LAN traffic (RFC 1918) via eth0 so responses reach local clients
# even when Traefik runs with hostNetwork on the node's LAN IP.
ip route add 192.168.0.0/16 via "${DEFAULT_GW}" dev "${DEFAULT_IF}" table main
ip route add 172.16.0.0/12 via "${DEFAULT_GW}" dev "${DEFAULT_IF}" table main
echo "Added LAN routes via ${DEFAULT_GW}."

# --- Verify connectivity through VPN ---
sleep 2
VPN_IP=$(curl -sf --max-time 10 https://ifconfig.me || echo "unknown")
echo "VPN public IP: ${VPN_IP}"

# --- Start Tinyproxy in foreground ---
echo "Starting Tinyproxy on port 8888..."
exec tinyproxy -d
