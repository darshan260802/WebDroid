// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

import (
	"bufio"
	"crypto/ecdh"
	"crypto/rand"
	"encoding/base64"
	"encoding/binary"
	"encoding/hex"
	"fmt"
	"io"
	"net"
	"net/http"
	"net/netip"
	"net/url"
	"strings"
	"testing"
	"time"

	"golang.zx2c4.com/wireguard/conn"
	"golang.zx2c4.com/wireguard/device"
	"golang.zx2c4.com/wireguard/tun/netstack"
)

func keypair(t *testing.T) (priv, pub []byte) {
	k, err := ecdh.X25519().GenerateKey(rand.Reader)
	if err != nil {
		t.Fatal(err)
	}
	return k.Bytes(), k.PublicKey().Bytes()
}

func freeUDPPort(t *testing.T) int {
	c, err := net.ListenPacket("udp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	return c.LocalAddr().(*net.UDPAddr).Port
}

// startServer runs a WireGuard peer with an HTTP server at 10.9.0.1:80 inside its tunnel.
func startServer(t *testing.T, serverPriv, clientPub []byte, port int) func() {
	tunDev, tnet, err := netstack.CreateNetTUN([]netip.Addr{netip.MustParseAddr("10.9.0.1")}, nil, 1420)
	if err != nil {
		t.Fatal(err)
	}
	dev := device.NewDevice(tunDev, conn.NewDefaultBind(), device.NewLogger(device.LogLevelSilent, "srv"))
	cfg := fmt.Sprintf("private_key=%s\nlisten_port=%d\npublic_key=%s\nallowed_ip=10.9.0.2/32\n",
		hex.EncodeToString(serverPriv), port, hex.EncodeToString(clientPub))
	if err := dev.IpcSet(cfg); err != nil {
		t.Fatal(err)
	}
	if err := dev.Up(); err != nil {
		t.Fatal(err)
	}
	ln, err := tnet.ListenTCP(&net.TCPAddr{IP: net.ParseIP("10.9.0.1"), Port: 80})
	if err != nil {
		t.Fatal(err)
	}
	srv := &http.Server{Handler: http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprintf(w, "hello via wireguard %s", r.URL.Path)
	})}
	go srv.Serve(ln)
	return func() { srv.Close(); dev.Close() }
}

func proxiedClient(port int) *http.Client {
	proxyURL, _ := url.Parse(fmt.Sprintf("http://127.0.0.1:%d", port))
	return &http.Client{Timeout: 20 * time.Second, Transport: &http.Transport{Proxy: http.ProxyURL(proxyURL)}}
}

func TestWireGuardThroughRouter(t *testing.T) {
	serverPriv, serverPub := keypair(t)
	clientPriv, clientPub := keypair(t)
	udp := freeUDPPort(t)
	defer startServer(t, serverPriv, clientPub, udp)()

	port, err := Start()
	if err != nil {
		t.Fatal(err)
	}
	defer Stop()
	conf := fmt.Sprintf(`[Interface]
PrivateKey = %s
Address = 10.9.0.2/32
DNS = 10.9.0.1

[Peer]
PublicKey = %s
Endpoint = 127.0.0.1:%d
AllowedIPs = 10.9.0.0/24
`, base64.StdEncoding.EncodeToString(clientPriv), base64.StdEncoding.EncodeToString(serverPub), udp)
	if err := ValidateWireGuard(conf); err != nil {
		t.Fatal(err)
	}
	if err := SetUpstream("wg", "wireguard", conf); err != nil {
		t.Fatal(err)
	}
	if err := SetRules(`{"10.9.0.1":"wg"}`); err != nil {
		t.Fatal(err)
	}

	// Plain HTTP (absolute-URI proxy request).
	resp, err := proxiedClient(port).Get("http://10.9.0.1/plain")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "hello via wireguard /plain" {
		t.Fatalf("unexpected body %q", body)
	}

	// CONNECT tunnel, as WebView uses for HTTPS.
	c, err := net.Dial("tcp", fmt.Sprintf("127.0.0.1:%d", port))
	if err != nil {
		t.Fatal(err)
	}
	defer c.Close()
	fmt.Fprintf(c, "CONNECT 10.9.0.1:80 HTTP/1.1\r\nHost: 10.9.0.1:80\r\n\r\n")
	br := bufio.NewReader(c)
	status, _ := br.ReadString('\n')
	if !strings.Contains(status, "200") {
		t.Fatalf("CONNECT failed: %q", status)
	}
	for {
		line, _ := br.ReadString('\n')
		if line == "\r\n" || line == "" {
			break
		}
	}
	fmt.Fprintf(c, "GET /tunnel HTTP/1.1\r\nHost: 10.9.0.1\r\nConnection: close\r\n\r\n")
	tunneled, _ := http.ReadResponse(br, nil)
	if tunneled == nil {
		t.Fatal("no response through tunnel")
	}
	tb, _ := io.ReadAll(tunneled.Body)
	if string(tb) != "hello via wireguard /tunnel" {
		t.Fatalf("unexpected tunneled body %q", tb)
	}

	if out, err := Check("wg", "http://10.9.0.1/check", 10); err != nil || !strings.Contains(out, "/check") {
		t.Fatalf("Check: %q %v", out, err)
	}
	if !strings.Contains(Status(), `"state":"up"`) {
		t.Fatalf("status: %s", Status())
	}
}

// socksServer is a minimal SOCKS5 server recording the requested host.
func socksServer(t *testing.T, target string, seen chan<- string) (string, func()) {
	ln, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go func() {
		for {
			c, err := ln.Accept()
			if err != nil {
				return
			}
			go func(c net.Conn) {
				defer c.Close()
				buf := make([]byte, 262)
				io.ReadFull(c, buf[:2])
				io.ReadFull(c, buf[:buf[1]])
				c.Write([]byte{5, 0})
				io.ReadFull(c, buf[:4])
				var host string
				switch buf[3] {
				case 3:
					io.ReadFull(c, buf[:1])
					n := int(buf[0])
					io.ReadFull(c, buf[:n])
					host = string(buf[:n])
				case 1:
					io.ReadFull(c, buf[:4])
					host = net.IP(buf[:4]).String()
				}
				io.ReadFull(c, buf[:2])
				port := binary.BigEndian.Uint16(buf[:2])
				seen <- fmt.Sprintf("%s:%d", host, port)
				up, err := net.Dial("tcp", target)
				if err != nil {
					c.Write([]byte{5, 1, 0, 1, 0, 0, 0, 0, 0, 0})
					return
				}
				defer up.Close()
				c.Write([]byte{5, 0, 0, 1, 0, 0, 0, 0, 0, 0})
				go io.Copy(up, c)
				io.Copy(c, up)
			}(c)
		}
	}()
	return ln.Addr().String(), func() { ln.Close() }
}

func TestSocksKeepsHostNamesAndFailsClosed(t *testing.T) {
	origin, err := net.Listen("tcp", "127.0.0.1:0")
	if err != nil {
		t.Fatal(err)
	}
	go http.Serve(origin, http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		fmt.Fprint(w, "via socks")
	}))
	defer origin.Close()
	seen := make(chan string, 4)
	addr, stop := socksServer(t, origin.Addr().String(), seen)
	defer stop()

	port, err := Start()
	if err != nil {
		t.Fatal(err)
	}
	defer Stop()
	if err := SetUpstream("tor", "socks5", addr); err != nil {
		t.Fatal(err)
	}
	if err := SetRules(`{"example.onion":"tor","blocked.test":"missing"}`); err != nil {
		t.Fatal(err)
	}
	resp, err := proxiedClient(port).Get("http://www.example.onion/")
	if err != nil {
		t.Fatal(err)
	}
	body, _ := io.ReadAll(resp.Body)
	resp.Body.Close()
	if string(body) != "via socks" {
		t.Fatalf("unexpected body %q", body)
	}
	if got := <-seen; got != "www.example.onion:80" {
		t.Fatalf("SOCKS saw %q, want the host name (remote DNS)", got)
	}
	if Route("a.b.example.onion") != "tor" || Route("other.test") != "" {
		t.Fatal("routing by domain suffix is wrong")
	}

	// A routed host whose upstream is missing must fail, not go direct.
	r, _ := current()
	r.mu.Lock()
	r.rules["blocked.test"] = "missing"
	r.mu.Unlock()
	done := make(chan *http.Response, 1)
	go func() {
		c := proxiedClient(port)
		c.Timeout = 120 * time.Second
		resp, _ := c.Get("http://blocked.test/")
		done <- resp
	}()
	select {
	case resp := <-done:
		if resp != nil && resp.StatusCode != http.StatusBadGateway {
			t.Fatalf("missing upstream answered %d", resp.StatusCode)
		}
	case <-time.After(2 * time.Second):
		// Still waiting for the upstream: also fail-closed. Set it now and expect success.
		SetUpstream("missing", "socks5", addr)
		resp := <-done
		if resp == nil || resp.StatusCode != 200 {
			t.Fatalf("waiting request didn't continue once the upstream appeared")
		}
	}
}

func TestParseWireGuardErrors(t *testing.T) {
	if err := ValidateWireGuard("[Interface]\nAddress = 10.0.0.2/32\n"); err == nil {
		t.Fatal("missing key accepted")
	}
	if _, err := newProxy("socks5", "nohost"); err == nil {
		t.Fatal("bad proxy accepted")
	}
	p, err := newProxy("http", "user:p@ss@proxy.example:8080")
	if err != nil || p.user != "user" || p.pass != "p@ss" || p.addr != "proxy.example:8080" {
		t.Fatalf("proxy parse: %+v %v", p, err)
	}
}
