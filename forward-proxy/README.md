# Forward Proxy with ExpressVPN (OpenVPN)

A Docker-based forward proxy that routes traffic through an ExpressVPN tunnel using OpenVPN. Run it on any machine in your network and point other devices at it to selectively route their traffic through the VPN.

## How it works

```
LAN client ──► host:8888 ──► Tinyproxy ──► OpenVPN tunnel ──► internet
```

- **Tinyproxy** accepts HTTP and HTTPS (via CONNECT) requests on port 8888
- **OpenVPN** connects using ExpressVPN's `.ovpn` config files, tunneling all outbound proxy traffic
- The host machine's own networking is **not affected** — only traffic sent to the proxy goes through the VPN

## Quick start

### 1. Get your ExpressVPN OpenVPN credentials and config

1. Log in to [expressvpn.com/setup#manual](https://www.expressvpn.com/setup#manual)
2. Select **Manual Configuration** and then **OpenVPN**
3. Copy your **username** and **password** (these are *not* your account credentials)
4. Download `.ovpn` config files for each server location you want to use

### 2. Configure

```bash
cp .env.example .env
```

Edit `.env` with your OpenVPN credentials:

```
OPENVPN_USER=your_openvpn_username
OPENVPN_PASS=your_openvpn_password
```

Place your downloaded `.ovpn` files in `vpn-config/`, named by country:

```bash
cp ~/Downloads/my_expressvpn_usa_-_new_york.ovpn vpn-config/usa.ovpn
cp ~/Downloads/my_expressvpn_uk_-_london.ovpn vpn-config/uk.ovpn
cp ~/Downloads/my_expressvpn_japan_-_tokyo.ovpn vpn-config/japan.ovpn
```

Set which country to use in `.env`:

```
VPN_COUNTRY=usa
```

If `VPN_COUNTRY` is left empty, the first `.ovpn` file found is used.

### 3. Build and run

```bash
docker compose up -d --build
```

### 4. Use from any device on your LAN

Set your HTTP proxy to `<docker-host-ip>:8888`. For example:

```bash
# Quick test — should return the VPN's IP, not your home IP
curl -x http://192.168.1.100:8888 https://ifconfig.me

# Set as shell proxy
export http_proxy=http://192.168.1.100:8888
export https_proxy=http://192.168.1.100:8888
```

## Switching VPN locations

Change the `VPN_COUNTRY` variable in `.env` and restart:

```bash
# Edit .env: VPN_COUNTRY=uk
docker compose restart
```

Or override inline without editing `.env`:

```bash
VPN_COUNTRY=japan docker compose up -d
```

## Configuration

### Environment variables

| Variable | Required | Default | Description |
|---|---|---|---|
| `OPENVPN_USER` | Yes | — | OpenVPN username from ExpressVPN manual setup page |
| `OPENVPN_PASS` | Yes | — | OpenVPN password from ExpressVPN manual setup page |
| `VPN_COUNTRY` | No | *(first file found)* | Country config to use — matches filename in `vpn-config/` without `.ovpn` (e.g. `usa`, `uk`, `japan`) |

### Tinyproxy

Edit `tinyproxy.conf` to customize proxy behavior. Key settings:

- **`Allow`** — Controls which IP ranges can use the proxy. Defaults to all private ranges (`10.0.0.0/8`, `172.16.0.0/12`, `192.168.0.0/16`).
- **`Port`** — Listening port (default `8888`). Change in both `tinyproxy.conf` and `docker-compose.yml` if modified.
- **`MaxClients`** — Max concurrent connections (default `100`).

## Logs

```bash
# All container logs (Tinyproxy output)
docker compose logs -f

# OpenVPN logs specifically
docker compose exec forward-proxy cat /var/log/openvpn.log

# Check VPN public IP
docker compose exec forward-proxy curl -s https://ifconfig.me
```

## Stopping

```bash
docker compose down
```

## Security notes

- The `.env` file contains your VPN credentials and is excluded from git via `.gitignore`.
- The `vpn-config/` directory may contain sensitive `.ovpn` files and is also excluded from git.
- The proxy allows all RFC 1918 private ranges by default. Restrict the `Allow` directives in `tinyproxy.conf` if you need tighter access control.
- IPv6 is disabled inside the container to prevent traffic leaking outside the VPN tunnel.
- VPN credentials are written to a file inside the container at runtime and are not baked into the image.
