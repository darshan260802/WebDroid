// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"
)

// router is a local HTTP proxy (CONNECT and absolute-URI requests) that
// sends each connection to the upstream its host is routed to. WebView only
// sends it the hosts of web apps that use a VPN; anything else it receives
// without a route goes out directly. Routed hosts never fall back to the
// direct network: if their tunnel is down the request fails.
type router struct {
	ln net.Listener

	mu         sync.RWMutex
	rules      map[string]string // domain suffix -> upstream id
	upstreams  map[string]upstream
	transports map[string]*http.Transport
	changed    chan struct{} // closed and replaced when upstreams change

	active atomic.Int64
	direct directUpstream

	tunMu   sync.Mutex
	tunnels map[*liveTunnel]struct{}
}

// liveTunnel is an open CONNECT tunnel and the upstream it went out through.
type liveTunnel struct {
	host           string
	id             string
	u              upstream
	client, remote net.Conn
}

func newRouter() *router {
	return &router{
		rules:      map[string]string{},
		upstreams:  map[string]upstream{},
		transports: map[string]*http.Transport{},
		changed:    make(chan struct{}),
		tunnels:    map[*liveTunnel]struct{}{},
	}
}

func (r *router) start() (int, error) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		return 0, err
	}
	r.ln = ln
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				if errors.Is(err, net.ErrClosed) {
					return
				}
				time.Sleep(50 * time.Millisecond)
				continue
			}
			go r.serve(c)
		}
	}()
	return ln.Addr().(*net.TCPAddr).Port, nil
}

func (r *router) stop() {
	if r.ln != nil {
		_ = r.ln.Close()
	}
	r.mu.Lock()
	for _, u := range r.upstreams {
		u.Close()
	}
	r.upstreams = map[string]upstream{}
	r.transports = map[string]*http.Transport{}
	r.mu.Unlock()
}

func normalizeHost(h string) string {
	h = strings.ToLower(strings.TrimSuffix(strings.TrimSpace(h), "."))
	if host, _, err := net.SplitHostPort(h); err == nil {
		h = host
	}
	return strings.Trim(h, "[]")
}

// routeFor finds the most specific rule: the host itself, then each parent domain.
func (r *router) routeFor(host string) (string, bool) {
	host = normalizeHost(host)
	r.mu.RLock()
	defer r.mu.RUnlock()
	for h := host; h != ""; {
		if id, ok := r.rules[h]; ok {
			return id, true
		}
		i := strings.IndexByte(h, '.')
		if i < 0 {
			break
		}
		h = h[i+1:]
	}
	return "", false
}

// sameSpec reports whether two upstreams are configured identically.
func sameSpec(a, b upstream) bool {
	sa, ok1 := a.(interface{ spec() string })
	sb, ok2 := b.(interface{ spec() string })
	return ok1 && ok2 && sa.spec() == sb.spec()
}

func (r *router) setUpstream(id string, u upstream) {
	r.mu.Lock()
	if old, ok := r.upstreams[id]; ok {
		if u != nil && sameSpec(old, u) {
			// Unchanged: keep the running tunnel and its connections.
			r.mu.Unlock()
			return
		}
		old.Close()
	}
	if t, ok := r.transports[id]; ok {
		t.CloseIdleConnections()
		delete(r.transports, id)
	}
	if u == nil {
		delete(r.upstreams, id)
	} else {
		r.upstreams[id] = u
	}
	close(r.changed)
	r.changed = make(chan struct{})
	r.mu.Unlock()
	r.closeStale()
}

// closeStale closes tunnels whose host now routes elsewhere (or whose
// upstream was replaced), so a site switched to another VPN or exit
// country stops seeing the old address as soon as it reconnects.
func (r *router) closeStale() {
	r.tunMu.Lock()
	open := make([]*liveTunnel, 0, len(r.tunnels))
	for t := range r.tunnels {
		open = append(open, t)
	}
	r.tunMu.Unlock()
	for _, t := range open {
		id, routed := r.routeFor(t.host)
		if !routed {
			id = "direct"
		}
		stale := id != t.id
		if !stale && id != "direct" {
			r.mu.RLock()
			cur, ok := r.upstreams[id]
			r.mu.RUnlock()
			stale = !ok || cur != t.u
		}
		if stale {
			_ = t.client.Close()
			_ = t.remote.Close()
		}
	}
}

// upstreamFor waits (up to the context) for a routed upstream to be
// configured: Tor, for one, gets its port only after it starts.
func (r *router) upstreamFor(ctx context.Context, host string) (upstream, string, error) {
	id, routed := r.routeFor(host)
	if !routed || id == "direct" {
		return &r.direct, "direct", nil
	}
	for {
		r.mu.RLock()
		u, ok := r.upstreams[id]
		changed := r.changed
		r.mu.RUnlock()
		if ok {
			return u, id, nil
		}
		select {
		case <-changed:
		case <-ctx.Done():
			return nil, id, errors.New("VPN " + id + " is not connected")
		}
	}
}

func (r *router) transportFor(id string, u upstream) *http.Transport {
	r.mu.Lock()
	defer r.mu.Unlock()
	if t, ok := r.transports[id]; ok {
		return t
	}
	t := &http.Transport{
		DialContext:           u.Dial,
		Proxy:                 nil,
		MaxIdleConnsPerHost:   4,
		IdleConnTimeout:       60 * time.Second,
		ResponseHeaderTimeout: 60 * time.Second,
		DisableCompression:    true,
	}
	r.transports[id] = t
	return t
}

var hopHeaders = []string{
	"Connection", "Proxy-Connection", "Keep-Alive", "Proxy-Authenticate",
	"Proxy-Authorization", "Te", "Trailer", "Transfer-Encoding", "Upgrade",
}

func writeStatus(c net.Conn, code int, msg string) {
	_, _ = fmt.Fprintf(c, "HTTP/1.1 %d %s\r\nContent-Type: text/plain; charset=utf-8\r\nConnection: close\r\nContent-Length: %d\r\n\r\n%s",
		code, http.StatusText(code), len(msg), msg)
}

func (r *router) serve(c net.Conn) {
	r.active.Add(1)
	defer r.active.Add(-1)
	defer c.Close()
	br := bufio.NewReader(c)
	for {
		_ = c.SetReadDeadline(time.Now().Add(2 * time.Minute))
		req, err := http.ReadRequest(br)
		if err != nil {
			return
		}
		_ = c.SetReadDeadline(time.Time{})
		if req.Method == http.MethodConnect {
			r.tunnel(c, br, req)
			return
		}
		if !r.forward(c, req) {
			return
		}
	}
}

// tunnel handles CONNECT host:port (HTTPS, WebSockets, HTTP/2).
func (r *router) tunnel(c net.Conn, br *bufio.Reader, req *http.Request) {
	target := req.Host
	if _, _, err := net.SplitHostPort(target); err != nil {
		target = net.JoinHostPort(target, "443")
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	u, id, err := r.upstreamFor(ctx, target)
	var remote net.Conn
	if err == nil {
		remote, err = u.Dial(ctx, "tcp", target)
	}
	cancel()
	if err != nil {
		writeStatus(c, http.StatusBadGateway, "WebDroid VPN ("+id+"): "+err.Error())
		return
	}
	defer remote.Close()
	live := &liveTunnel{host: target, id: id, u: u, client: c, remote: remote}
	r.tunMu.Lock()
	r.tunnels[live] = struct{}{}
	r.tunMu.Unlock()
	defer func() {
		r.tunMu.Lock()
		delete(r.tunnels, live)
		r.tunMu.Unlock()
	}()
	if _, err := io.WriteString(c, "HTTP/1.1 200 Connection Established\r\n\r\n"); err != nil {
		return
	}
	done := make(chan struct{}, 2)
	go func() {
		if n := br.Buffered(); n > 0 {
			buf, _ := br.Peek(n)
			_, _ = remote.Write(buf)
			_, _ = br.Discard(n)
		}
		_, _ = io.Copy(remote, c)
		if cw, ok := remote.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	go func() {
		_, _ = io.Copy(c, remote)
		if cw, ok := c.(interface{ CloseWrite() error }); ok {
			_ = cw.CloseWrite()
		}
		done <- struct{}{}
	}()
	<-done
	// Give the other direction a moment to drain, then tear down.
	select {
	case <-done:
	case <-time.After(5 * time.Second):
	}
}

// forward handles plain-HTTP proxy requests (absolute URIs). Returns whether
// the client connection can be reused.
func (r *router) forward(c net.Conn, req *http.Request) bool {
	if req.URL == nil || req.URL.Host == "" {
		writeStatus(c, http.StatusBadRequest, "not a proxy request")
		return false
	}
	ctx, cancel := context.WithTimeout(context.Background(), 90*time.Second)
	defer cancel()
	u, id, err := r.upstreamFor(ctx, req.URL.Host)
	if err != nil {
		writeStatus(c, http.StatusBadGateway, "WebDroid VPN ("+id+"): "+err.Error())
		return false
	}
	out := req.Clone(context.Background())
	out.RequestURI = ""
	for _, h := range hopHeaders {
		out.Header.Del(h)
	}
	resp, err := r.transportFor(id, u).RoundTrip(out)
	if err != nil {
		writeStatus(c, http.StatusBadGateway, "WebDroid VPN ("+id+"): "+err.Error())
		return false
	}
	defer resp.Body.Close()
	for _, h := range hopHeaders {
		if h != "Transfer-Encoding" {
			resp.Header.Del(h)
		}
	}
	keep := !req.Close && !resp.Close
	if err := resp.Write(c); err != nil {
		return false
	}
	return keep
}
