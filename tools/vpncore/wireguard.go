// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"net"
	"net/netip"
	"strconv"
	"strings"
	"sync"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

// wgConfig is the subset of a wg-quick .conf file a userspace tunnel needs.
type wgConfig struct {
	privateKey string // hex
	addresses  []netip.Addr
	dns        []netip.Addr
	mtu        int
	peers      []wgPeer
}

type wgPeer struct {
	publicKey    string // hex
	presharedKey string // hex
	endpoint     string // host:port
	allowedIPs   []string
	keepalive    int
}

func keyToHex(b64 string) (string, error) {
	raw, err := base64.StdEncoding.DecodeString(strings.TrimSpace(b64))
	if err != nil || len(raw) != 32 {
		return "", fmt.Errorf("invalid key")
	}
	return hex.EncodeToString(raw), nil
}

func splitList(v string) []string {
	var out []string
	for _, p := range strings.Split(v, ",") {
		if p = strings.TrimSpace(p); p != "" {
			out = append(out, p)
		}
	}
	return out
}

// parseWireGuard reads a wg-quick style config ([Interface] + [Peer] sections).
func parseWireGuard(text string) (*wgConfig, error) {
	cfg := &wgConfig{mtu: 1280}
	section := ""
	var peer *wgPeer
	sc := bufio.NewScanner(strings.NewReader(text))
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if i := strings.IndexAny(line, "#;"); i >= 0 {
			line = strings.TrimSpace(line[:i])
		}
		if line == "" {
			continue
		}
		if strings.HasPrefix(line, "[") && strings.HasSuffix(line, "]") {
			section = strings.ToLower(strings.Trim(line, "[]"))
			if section == "peer" {
				cfg.peers = append(cfg.peers, wgPeer{})
				peer = &cfg.peers[len(cfg.peers)-1]
			}
			continue
		}
		eq := strings.IndexByte(line, '=')
		if eq < 0 {
			continue
		}
		key := strings.ToLower(strings.TrimSpace(line[:eq]))
		val := strings.TrimSpace(line[eq+1:])
		switch section {
		case "interface":
			switch key {
			case "privatekey":
				k, err := keyToHex(val)
				if err != nil {
					return nil, errors.New("PrivateKey is not a valid WireGuard key")
				}
				cfg.privateKey = k
			case "address":
				for _, a := range splitList(val) {
					if p, err := netip.ParsePrefix(a); err == nil {
						cfg.addresses = append(cfg.addresses, p.Addr())
					} else if ip, err := netip.ParseAddr(a); err == nil {
						cfg.addresses = append(cfg.addresses, ip)
					}
				}
			case "dns":
				for _, a := range splitList(val) {
					if ip, err := netip.ParseAddr(a); err == nil {
						cfg.dns = append(cfg.dns, ip)
					}
				}
			case "mtu":
				if n, err := strconv.Atoi(val); err == nil && n >= 576 && n <= 9000 {
					cfg.mtu = n
				}
			}
		case "peer":
			if peer == nil {
				continue
			}
			switch key {
			case "publickey":
				k, err := keyToHex(val)
				if err != nil {
					return nil, errors.New("PublicKey is not a valid WireGuard key")
				}
				peer.publicKey = k
			case "presharedkey":
				k, err := keyToHex(val)
				if err != nil {
					return nil, errors.New("PresharedKey is not a valid WireGuard key")
				}
				peer.presharedKey = k
			case "endpoint":
				peer.endpoint = val
			case "allowedips":
				peer.allowedIPs = append(peer.allowedIPs, splitList(val)...)
			case "persistentkeepalive":
				if n, err := strconv.Atoi(val); err == nil {
					peer.keepalive = n
				}
			}
		}
	}
	if cfg.privateKey == "" {
		return nil, errors.New("the config has no [Interface] PrivateKey")
	}
	if len(cfg.addresses) == 0 {
		return nil, errors.New("the config has no [Interface] Address")
	}
	if len(cfg.peers) == 0 || cfg.peers[0].publicKey == "" || cfg.peers[0].endpoint == "" {
		return nil, errors.New("the config needs a [Peer] with PublicKey and Endpoint")
	}
	if len(cfg.dns) == 0 {
		// Resolve names inside the tunnel even when the config names no DNS.
		cfg.dns = []netip.Addr{netip.MustParseAddr("1.1.1.1"), netip.MustParseAddr("9.9.9.9")}
	}
	return cfg, nil
}

// uapi renders the config in wireguard-go's IPC format, resolving endpoints.
func (c *wgConfig) uapi(ctx context.Context) (string, error) {
	var b strings.Builder
	fmt.Fprintf(&b, "private_key=%s\nreplace_peers=true\n", c.privateKey)
	for _, p := range c.peers {
		host, port, err := net.SplitHostPort(p.endpoint)
		if err != nil {
			return "", fmt.Errorf("bad Endpoint %q", p.endpoint)
		}
		addr, err := netip.ParseAddr(host)
		if err != nil {
			ips, err := net.DefaultResolver.LookupNetIP(ctx, "ip", host)
			if err != nil || len(ips) == 0 {
				return "", fmt.Errorf("couldn't resolve %s", host)
			}
			addr = ips[0]
			for _, ip := range ips {
				if ip.Is4() {
					addr = ip
					break
				}
			}
		}
		fmt.Fprintf(&b, "public_key=%s\n", p.publicKey)
		if p.presharedKey != "" {
			fmt.Fprintf(&b, "preshared_key=%s\n", p.presharedKey)
		}
		fmt.Fprintf(&b, "endpoint=%s\n", netip.AddrPortFrom(addr.Unmap(), mustPort(port)))
		keepalive := p.keepalive
		if keepalive == 0 {
			keepalive = 25
		}
		fmt.Fprintf(&b, "persistent_keepalive_interval=%d\nreplace_allowed_ips=true\n", keepalive)
		allowed := p.allowedIPs
		if len(allowed) == 0 {
			allowed = []string{"0.0.0.0/0", "::/0"}
		}
		for _, a := range allowed {
			fmt.Fprintf(&b, "allowed_ip=%s\n", a)
		}
	}
	return b.String(), nil
}

func mustPort(p string) uint16 {
	n, _ := strconv.Atoi(p)
	return uint16(n)
}

// wgUpstream is a userspace WireGuard tunnel (gVisor netstack): no system
// VPN, no root; only connections WebDroid sends to it use the tunnel.
type wgUpstream struct {
	cfg *wgConfig

	mu       sync.Mutex
	dev      *device.Device
	tnet     *netstack.Net
	lastErr  error
	started  time.Time
	counters counters
}

func newWireGuard(text string) (*wgUpstream, error) {
	cfg, err := parseWireGuard(text)
	if err != nil {
		return nil, err
	}
	return &wgUpstream{cfg: cfg}, nil
}

func (w *wgUpstream) ensure(ctx context.Context) (*netstack.Net, error) {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.tnet != nil {
		return w.tnet, nil
	}
	uapi, err := w.cfg.uapi(ctx)
	if err != nil {
		w.lastErr = err
		return nil, err
	}
	tunDev, tnet, err := netstack.CreateNetTUN(w.cfg.addresses, w.cfg.dns, w.cfg.mtu)
	if err != nil {
		w.lastErr = err
		return nil, err
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, "wg"))
	if err := dev.IpcSet(uapi); err != nil {
		dev.Close()
		w.lastErr = err
		return nil, err
	}
	if err := dev.Up(); err != nil {
		dev.Close()
		w.lastErr = err
		return nil, err
	}
	w.dev, w.tnet, w.started, w.lastErr = dev, tnet, time.Now(), nil
	return tnet, nil
}

func (w *wgUpstream) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	tnet, err := w.ensure(ctx)
	if err != nil {
		return nil, err
	}
	c, err := tnet.DialContext(ctx, network, addr)
	if err != nil {
		w.mu.Lock()
		w.lastErr = err
		w.mu.Unlock()
		return nil, err
	}
	return w.counters.wrap(c), nil
}

func (w *wgUpstream) Close() {
	w.mu.Lock()
	defer w.mu.Unlock()
	if w.dev != nil {
		w.dev.Close()
	}
	w.dev, w.tnet = nil, nil
}

func (w *wgUpstream) Status() map[string]any {
	w.mu.Lock()
	defer w.mu.Unlock()
	s := map[string]any{"type": "wireguard", "rx": w.counters.rx.Load(), "tx": w.counters.tx.Load()}
	switch {
	case w.tnet != nil:
		s["state"] = "up"
		if hs := w.lastHandshakeLocked(); hs > 0 {
			s["handshake"] = hs
		}
	case w.lastErr != nil:
		s["state"] = "error"
	default:
		s["state"] = "idle"
	}
	if w.lastErr != nil {
		s["error"] = w.lastErr.Error()
	}
	return s
}

// lastHandshakeLocked reads the latest handshake time (unix seconds) from the device.
func (w *wgUpstream) lastHandshakeLocked() int64 {
	if w.dev == nil {
		return 0
	}
	state, err := w.dev.IpcGet()
	if err != nil {
		return 0
	}
	var latest int64
	for _, line := range strings.Split(state, "\n") {
		if v, ok := strings.CutPrefix(line, "last_handshake_time_sec="); ok {
			if n, err := strconv.ParseInt(v, 10, 64); err == nil && n > latest {
				latest = n
			}
		}
	}
	return latest
}
