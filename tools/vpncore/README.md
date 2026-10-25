# vpncore

WebDroid's per-site VPN, in Go, bound to Kotlin with gomobile.

- `router.go`: a local HTTP proxy (CONNECT + absolute URIs) on 127.0.0.1.
  WebView's ProxyController sends it only the domains of web apps that use a
  VPN; it forwards each domain to that app's upstream and never falls back to
  the direct network for a routed domain.
- `wireguard.go`: userspace WireGuard (wireguard-go + gVisor netstack) from a
  wg-quick `.conf`. No system VPN, no root.
- `upstream.go`: SOCKS5 (Tor, Orbot…) and HTTP CONNECT proxies, with host
  names passed through so DNS resolves on the far side.

`go test ./...` runs a WireGuard tunnel between two in-process peers, a SOCKS5
upstream and the fail-closed behaviour. `./build.sh` produces
`app/libs/vpncore.aar` (CI does this before the Gradle build).
