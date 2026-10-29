// SPDX-License-Identifier: GPL-3.0-or-later

// Package vpncore is WebDroid's per-site VPN: a local proxy that WebView
// sends chosen sites through, and the tunnels behind it (userspace
// WireGuard, SOCKS5 such as Tor, or HTTP proxies). It is bound to Kotlin
// with gomobile; every exported function is safe to call from any thread.
package vpncore

import (
	"context"
	"encoding/json"
	"errors"
	"io"
	"net/http"
	"strings"
	"sync"
	"time"
)

var (
	mu   sync.Mutex
	r    *router
	port int
)

// Start launches the local proxy on 127.0.0.1 (once) and returns its port.
func Start() (int, error) {
	mu.Lock()
	defer mu.Unlock()
	if r != nil {
		return port, nil
	}
	nr := newRouter()
	p, err := nr.start()
	if err != nil {
		return 0, err
	}
	r, port = nr, p
	return port, nil
}

// Stop closes the proxy and every tunnel.
func Stop() {
	mu.Lock()
	defer mu.Unlock()
	if r != nil {
		r.stop()
	}
	r, port = nil, 0
	stopSteering()
}

func current() (*router, error) {
	mu.Lock()
	defer mu.Unlock()
	if r == nil {
		return nil, errors.New("VPN router is not running")
	}
	return r, nil
}

// SetUpstream configures (or replaces) an upstream. kind is "wireguard"
// (config is a wg-quick .conf), "socks5" or "http" (config is
// [user:pass@]host:port). Connections waiting for this id continue.
func SetUpstream(id, kind, config string) error {
	rt, err := current()
	if err != nil {
		return err
	}
	var u upstream
	switch kind {
	case "wireguard":
		u, err = newWireGuard(config)
	case "socks5", "http":
		u, err = newProxy(kind, config)
	default:
		err = errors.New("unknown VPN type " + kind)
	}
	if err != nil {
		return err
	}
	rt.setUpstream(id, u)
	return nil
}

// ValidateWireGuard checks a wg-quick config without starting it.
func ValidateWireGuard(config string) error {
	_, err := parseWireGuard(config)
	return err
}

// RemoveUpstream closes an upstream; hosts routed to it then fail until it
// is set again (they never leak to the direct network).
func RemoveUpstream(id string) {
	if rt, err := current(); err == nil {
		rt.setUpstream(id, nil)
		updateSteering(rt)
	}
}

// SetRules replaces all routing rules. rules is a JSON object mapping a
// domain (matching itself and its subdomains) to an upstream id.
func SetRules(rules string) error {
	rt, err := current()
	if err != nil {
		return err
	}
	parsed := map[string]string{}
	if err := json.Unmarshal([]byte(rules), &parsed); err != nil {
		return err
	}
	clean := make(map[string]string, len(parsed))
	for k, v := range parsed {
		if k = normalizeHost(k); k != "" && v != "" {
			clean[k] = v
		}
	}
	rt.mu.Lock()
	rt.rules = clean
	rt.mu.Unlock()
	rt.closeStale()
	return nil
}

// AddRule routes one more domain (and its subdomains) to an upstream.
func AddRule(domain, id string) {
	rt, err := current()
	if err != nil {
		return
	}
	domain = normalizeHost(domain)
	if domain == "" || id == "" {
		return
	}
	rt.mu.Lock()
	changed := rt.rules[domain] != id
	rt.rules[domain] = id
	rt.mu.Unlock()
	if changed {
		rt.closeStale()
	}
}

// Route returns the upstream id a host goes to ("" when direct).
func Route(host string) string {
	rt, err := current()
	if err != nil {
		return ""
	}
	id, _ := rt.routeFor(host)
	return id
}

// Status returns JSON: {"port":n,"active":n,"upstreams":{id:{state,type,rx,tx,error?,handshake?}}}.
func Status() string {
	rt, err := current()
	if err != nil {
		return `{"running":false}`
	}
	rt.mu.RLock()
	ups := make(map[string]map[string]any, len(rt.upstreams))
	for id, u := range rt.upstreams {
		ups[id] = u.Status()
	}
	rt.mu.RUnlock()
	out, _ := json.Marshal(map[string]any{"running": true, "port": port, "active": rt.active.Load(), "upstreams": ups})
	return string(out)
}

// Check fetches url through an upstream and returns up to 4 KB of the body,
// e.g. https://www.cloudflare.com/cdn-cgi/trace to show the exit IP/country.
func Check(id, url string, timeoutSeconds int) (string, error) {
	rt, err := current()
	if err != nil {
		return "", err
	}
	if timeoutSeconds <= 0 {
		timeoutSeconds = 30
	}
	ctx, cancel := context.WithTimeout(context.Background(), time.Duration(timeoutSeconds)*time.Second)
	defer cancel()
	rt.mu.RLock()
	u, ok := rt.upstreams[id]
	rt.mu.RUnlock()
	if !ok {
		return "", errors.New("VPN " + id + " is not set up")
	}
	client := &http.Client{Transport: &http.Transport{DialContext: u.Dial, Proxy: nil}}
	defer client.CloseIdleConnections()
	req, err := http.NewRequestWithContext(ctx, http.MethodGet, url, nil)
	if err != nil {
		return "", err
	}
	req.Header.Set("User-Agent", "WebDroid")
	resp, err := client.Do(req)
	if err != nil {
		return "", simplify(err)
	}
	defer resp.Body.Close()
	body, _ := io.ReadAll(io.LimitReader(resp.Body, 4096))
	if resp.StatusCode >= 400 {
		return string(body), errors.New(resp.Status)
	}
	return string(body), nil
}

func simplify(err error) error {
	msg := err.Error()
	if i := strings.LastIndex(msg, ": "); i >= 0 && strings.Contains(msg, "Get \"") {
		msg = msg[i+2:]
	}
	return errors.New(msg)
}
