// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

import (
	"bufio"
	"context"
	"encoding/base64"
	"fmt"
	"net"
	"net/http"
	"strings"
	"sync"
	"sync/atomic"
	"time"

	"golang.org/x/net/proxy"
)

// upstream is somewhere connections can be sent: a tunnel or a proxy.
type upstream interface {
	Dial(ctx context.Context, network, addr string) (net.Conn, error)
	Close()
	Status() map[string]any
}

type counters struct{ rx, tx atomic.Int64 }

type countedConn struct {
	net.Conn
	c *counters
}

func (cc *countedConn) Read(p []byte) (int, error) {
	n, err := cc.Conn.Read(p)
	cc.c.rx.Add(int64(n))
	return n, err
}

func (cc *countedConn) Write(p []byte) (int, error) {
	n, err := cc.Conn.Write(p)
	cc.c.tx.Add(int64(n))
	return n, err
}

func (c *counters) wrap(conn net.Conn) net.Conn { return &countedConn{Conn: conn, c: c} }

// proxyUpstream forwards through a SOCKS5 (Tor, Orbot, ssh -D, Shadowsocks
// clients...) or HTTP CONNECT proxy. Host names are passed to the proxy, so
// DNS is resolved on the far side too.
type proxyUpstream struct {
	kind     string // "socks5" or "http"
	addr     string
	user     string
	pass     string
	mu       sync.Mutex
	lastErr  error
	lastOK   time.Time
	counters counters
}

// newProxy parses "host:port" or "user:pass@host:port".
func newProxy(kind, spec string) (*proxyUpstream, error) {
	spec = strings.TrimSpace(spec)
	spec = strings.TrimPrefix(strings.TrimPrefix(spec, "socks5://"), "http://")
	p := &proxyUpstream{kind: kind}
	if at := strings.LastIndexByte(spec, '@'); at >= 0 {
		cred := spec[:at]
		spec = spec[at+1:]
		if i := strings.IndexByte(cred, ':'); i >= 0 {
			p.user, p.pass = cred[:i], cred[i+1:]
		} else {
			p.user = cred
		}
	}
	if _, _, err := net.SplitHostPort(spec); err != nil {
		return nil, fmt.Errorf("proxy address must be host:port")
	}
	p.addr = spec
	return p, nil
}

func (p *proxyUpstream) note(err error) {
	p.mu.Lock()
	defer p.mu.Unlock()
	if err != nil {
		p.lastErr = err
	} else {
		p.lastErr, p.lastOK = nil, time.Now()
	}
}

func (p *proxyUpstream) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	var c net.Conn
	var err error
	if p.kind == "socks5" {
		var auth *proxy.Auth
		if p.user != "" {
			auth = &proxy.Auth{User: p.user, Password: p.pass}
		}
		var d proxy.Dialer
		d, err = proxy.SOCKS5("tcp", p.addr, auth, &net.Dialer{Timeout: 15 * time.Second})
		if err == nil {
			if cd, ok := d.(proxy.ContextDialer); ok {
				c, err = cd.DialContext(ctx, network, addr)
			} else {
				c, err = d.Dial(network, addr)
			}
		}
	} else {
		c, err = p.dialConnect(ctx, addr)
	}
	p.note(err)
	if err != nil {
		return nil, err
	}
	return p.counters.wrap(c), nil
}

func (p *proxyUpstream) dialConnect(ctx context.Context, addr string) (net.Conn, error) {
	var d net.Dialer
	c, err := d.DialContext(ctx, "tcp", p.addr)
	if err != nil {
		return nil, err
	}
	if deadline, ok := ctx.Deadline(); ok {
		_ = c.SetDeadline(deadline)
	}
	req := "CONNECT " + addr + " HTTP/1.1\r\nHost: " + addr + "\r\n"
	if p.user != "" {
		req += "Proxy-Authorization: Basic " + base64.StdEncoding.EncodeToString([]byte(p.user+":"+p.pass)) + "\r\n"
	}
	req += "\r\n"
	if _, err := c.Write([]byte(req)); err != nil {
		c.Close()
		return nil, err
	}
	br := bufio.NewReader(c)
	resp, err := http.ReadResponse(br, nil)
	if err != nil {
		c.Close()
		return nil, err
	}
	if resp.StatusCode != http.StatusOK {
		c.Close()
		return nil, fmt.Errorf("proxy refused: %s", resp.Status)
	}
	_ = c.SetDeadline(time.Time{})
	if br.Buffered() > 0 {
		return &bufferedConn{Conn: c, r: br}, nil
	}
	return c, nil
}

func (p *proxyUpstream) Close() {}

func (p *proxyUpstream) Status() map[string]any {
	p.mu.Lock()
	defer p.mu.Unlock()
	s := map[string]any{"type": p.kind, "rx": p.counters.rx.Load(), "tx": p.counters.tx.Load()}
	switch {
	case p.lastErr != nil:
		s["state"] = "error"
		s["error"] = p.lastErr.Error()
	case !p.lastOK.IsZero():
		s["state"] = "up"
	default:
		s["state"] = "idle"
	}
	return s
}

type bufferedConn struct {
	net.Conn
	r *bufio.Reader
}

func (b *bufferedConn) Read(p []byte) (int, error) { return b.r.Read(p) }

// directUpstream is the normal network.
type directUpstream struct{ d net.Dialer }

func (d *directUpstream) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	return d.d.DialContext(ctx, network, addr)
}
func (d *directUpstream) Close()                 {}
func (d *directUpstream) Status() map[string]any { return map[string]any{"type": "direct", "state": "up"} }
