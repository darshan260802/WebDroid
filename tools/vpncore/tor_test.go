// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

import (
	"bufio"
	"encoding/base64"
	"encoding/hex"
	"fmt"
	"net"
	"os"
	"path/filepath"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeTor is a minimal Tor control port: it answers the commands the
// steering uses and emits CIRC/STREAM events.
type fakeTor struct {
	t        *testing.T
	ln       net.Listener
	wmu      sync.Mutex
	conn     net.Conn
	mu       sync.Mutex
	next     int
	leave    []string
	paths    map[string][]string // circuit id -> fingerprints
	attach   chan [2]string      // stream, circuit
	closed   chan string
	extends  chan []string
	failNext bool
}

// relays: fingerprint -> (ip, flags)
var fakeRelays = []struct {
	fp, ip, flags, policy string
}{
	{strings.Repeat("A", 40), "101.0.0.1", "Fast Guard Running Stable Valid", ""},           // guard, DE
	{strings.Repeat("B", 40), "102.0.0.1", "Fast Running Stable Valid", ""},                 // middle, FR
	{strings.Repeat("C", 40), "102.1.0.1", "Fast Running Valid", ""},                        // middle, FR
	{strings.Repeat("D", 40), "100.0.0.1", "Exit Fast Running Valid", "accept 80,443"},      // US exit
	{strings.Repeat("E", 40), "100.1.0.1", "Exit Fast Running Valid", "reject 1-65535"},     // US exit refusing all
	{strings.Repeat("F", 40), "101.1.0.1", "Exit Fast Running Valid", "accept 1-65535"},     // DE exit
	{strings.Repeat("9", 40), "100.2.0.1", "BadExit Exit Fast Running Valid", "accept 443"}, // US, bad
}

func newFakeTor(t *testing.T, sock string) *fakeTor {
	ln, err := net.Listen("unix", sock)
	if err != nil {
		t.Fatal(err)
	}
	f := &fakeTor{
		t: t, ln: ln, next: 100, paths: map[string][]string{},
		attach: make(chan [2]string, 16), closed: make(chan string, 16), extends: make(chan []string, 16),
	}
	// Tor's own circuit: guard A, middle B, exit F.
	f.paths["1"] = []string{fakeRelays[0].fp, fakeRelays[1].fp, fakeRelays[5].fp}
	go f.serve()
	return f
}

func (f *fakeTor) send(lines ...string) {
	f.wmu.Lock()
	defer f.wmu.Unlock()
	for _, l := range lines {
		fmt.Fprintf(f.conn, "%s\r\n", l)
	}
}

func pathString(fps []string) string {
	parts := make([]string, len(fps))
	for i, fp := range fps {
		parts[i] = "$" + fp + "~relay" + fmt.Sprint(i)
	}
	return strings.Join(parts, ",")
}

func (f *fakeTor) serve() {
	c, err := f.ln.Accept()
	if err != nil {
		return
	}
	f.wmu.Lock()
	f.conn = c
	f.wmu.Unlock()
	br := bufio.NewReader(c)
	for {
		line, err := br.ReadString('\n')
		if err != nil {
			return
		}
		cmd := strings.TrimRight(line, "\r\n")
		args := strings.Fields(cmd)
		switch {
		case args[0] == "AUTHENTICATE", args[0] == "SETEVENTS":
			f.send("250 OK")
		case args[0] == "SETCONF":
			f.mu.Lock()
			f.leave = append(f.leave, args[1])
			f.mu.Unlock()
			f.send("250 OK")
		case args[0] == "GETINFO" && args[1] == "circuit-status":
			f.send("250+circuit-status=", "1 BUILT "+pathString(f.paths["1"])+" BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL", ".", "250 OK")
		case args[0] == "GETINFO" && args[1] == "ns/all":
			lines := []string{"250+ns/all="}
			for _, r := range fakeRelays {
				id, _ := hex.DecodeString(r.fp)
				lines = append(lines,
					fmt.Sprintf("r nick%s %s AAAAAAAAAAAAAAAAAAAAAAAAAAA 2026-10-29 01:00:00 %s 9001 0", r.fp[:1], base64.RawStdEncoding.EncodeToString(id), r.ip),
					"s "+r.flags,
					"w Bandwidth=1000")
			}
			f.send(append(lines, ".", "250 OK")...)
		case args[0] == "GETINFO" && strings.HasPrefix(args[1], "md/id/"):
			fp := strings.TrimPrefix(args[1], "md/id/")
			policy := ""
			for _, r := range fakeRelays {
				if r.fp == fp {
					policy = r.policy
				}
			}
			lines := []string{"250+" + args[1] + "=", "onion-key", "-----BEGIN RSA PUBLIC KEY-----"}
			if policy != "" {
				lines = append(lines, "p "+policy)
			}
			f.send(append(lines, ".", "250 OK")...)
		case args[0] == "GETINFO":
			f.send("552 Unrecognized key")
		case args[0] == "EXTENDCIRCUIT":
			f.mu.Lock()
			f.next++
			id := fmt.Sprint(f.next)
			var path []string
			if len(args) > 2 && !strings.HasPrefix(args[2], "purpose=") {
				path = relayIDs(args[2])
			} else {
				path = []string{fakeRelays[0].fp, fakeRelays[2].fp, fakeRelays[5].fp}
			}
			f.paths[id] = path
			fail := f.failNext
			f.failNext = false
			f.mu.Unlock()
			f.send("250 EXTENDED " + id)
			f.extends <- path
			go func() {
				time.Sleep(20 * time.Millisecond)
				if fail {
					f.send("650 CIRC " + id + " FAILED " + pathString(path) + " PURPOSE=GENERAL REASON=TIMEOUT")
					return
				}
				f.send("650 CIRC "+id+" LAUNCHED BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL",
					"650 CIRC "+id+" BUILT "+pathString(path)+" BUILD_FLAGS=NEED_CAPACITY PURPOSE=GENERAL")
			}()
		case args[0] == "ATTACHSTREAM":
			f.send("250 OK")
			f.attach <- [2]string{args[1], args[2]}
		case args[0] == "CLOSESTREAM":
			f.send("250 OK")
			f.closed <- args[1]
		default:
			f.send("510 Unrecognized command")
		}
	}
}

func (f *fakeTor) newStream(id, user, target string) {
	f.send(fmt.Sprintf(`650 STREAM %s NEW 0 %s SOURCE_ADDR=127.0.0.1:40000 PURPOSE=USER SOCKS_USERNAME="%s" SOCKS_PASSWORD="webdroid"`, id, target, user))
}

func (f *fakeTor) exitOf(circ string) string {
	f.mu.Lock()
	defer f.mu.Unlock()
	p := f.paths[circ]
	if len(p) == 0 {
		return ""
	}
	return p[len(p)-1]
}

func waitFor[T any](t *testing.T, ch chan T, what string) T {
	t.Helper()
	select {
	case v := <-ch:
		return v
	case <-time.After(3 * time.Second):
		t.Fatalf("timed out waiting for %s", what)
		var zero T
		return zero
	}
}

func writeGeoIP(t *testing.T, dir string) string {
	// 100.0.0.0/8 US, 101.0.0.0/8 DE, 102.0.0.0/8 FR (integer form, as Tor ships it).
	text := "# test\n1677721600,1694498815,US\n1694498816,1711276031,DE\n1711276032,1728053247,FR\n"
	p := filepath.Join(dir, "geoip")
	if err := os.WriteFile(p, []byte(text), 0o644); err != nil {
		t.Fatal(err)
	}
	return p
}

func TestTorSteeringPicksExitCountryPerStream(t *testing.T) {
	dir, err := os.MkdirTemp("", "torctl")
	if err != nil {
		t.Fatal(err)
	}
	defer os.RemoveAll(dir)
	sock := filepath.Join(dir, "ControlSocket")
	fake := newFakeTor(t, sock)
	defer fake.ln.Close()
	geo, err := loadGeoIP(writeGeoIP(t, dir))
	if err != nil {
		t.Fatal(err)
	}
	if got := geo.country(net.ParseIP("101.2.3.4")); got != "de" {
		t.Fatalf("geoip: got %q", got)
	}

	s, err := startTorSteer(sock, geo)
	if err != nil {
		t.Fatal(err)
	}

	// A US stream gets a new circuit through the US exit that accepts 443
	// (not the one refusing everything, nor the BadExit), via Tor's guard.
	fake.newStream("10", "wd-us", "example.com:443")
	path := waitFor(t, fake.extends, "US circuit")
	if len(path) != 3 || path[0] != fakeRelays[0].fp || path[2] != fakeRelays[3].fp {
		t.Fatalf("US path = %v", path)
	}
	a := waitFor(t, fake.attach, "US attach")
	if a[0] != "10" || fake.exitOf(a[1]) != fakeRelays[3].fp {
		t.Fatalf("stream 10 attached to %v (exit %s)", a, fake.exitOf(a[1]))
	}
	usCirc := a[1]

	// A second US stream reuses that circuit at once.
	fake.newStream("11", "wd-us", "example.com:443")
	if a := waitFor(t, fake.attach, "US reuse"); a != [2]string{"11", usCirc} {
		t.Fatalf("stream 11 attached to %v, want %s", a, usCirc)
	}

	// A German stream gets its own circuit through the German exit.
	fake.newStream("12", "wd-de", "example.org:443")
	path = waitFor(t, fake.extends, "DE circuit")
	if path[2] != fakeRelays[5].fp {
		t.Fatalf("DE path = %v", path)
	}
	if a := waitFor(t, fake.attach, "DE attach"); a[0] != "12" || fake.exitOf(a[1]) != fakeRelays[5].fp {
		t.Fatalf("stream 12 attached to %v", a)
	}

	// No preference: Tor's own circuit.
	fake.newStream("13", "wd-any", "example.net:443")
	if a := waitFor(t, fake.attach, "any attach"); a != [2]string{"13", "1"} {
		t.Fatalf("stream 13 attached to %v", a)
	}

	// A country without exits fails the stream instead of leaking elsewhere.
	fake.newStream("14", "wd-jp", "example.jp:443")
	if id := waitFor(t, fake.closed, "JP close"); id != "14" {
		t.Fatalf("closed %s", id)
	}
	if p := s.problem("jp"); !strings.Contains(p, "JP") {
		t.Fatalf("problem = %q", p)
	}

	// A circuit that times out a stream is avoided; a failed build is retried.
	deCirc := ""
	fake.mu.Lock()
	for id, p := range fake.paths {
		if id != "1" && p[2] == fakeRelays[5].fp {
			deCirc = id
		}
	}
	fake.mu.Unlock()
	fake.mu.Lock()
	fake.failNext = true
	fake.mu.Unlock()
	fake.send(fmt.Sprintf(`650 STREAM 12 DETACHED %s example.org:443 REASON=TIMEOUT SOCKS_USERNAME="wd-de"`, deCirc))
	waitFor(t, fake.extends, "DE rebuild (fails)")
	waitFor(t, fake.extends, "DE rebuild (retry)")
	if a := waitFor(t, fake.attach, "DE re-attach"); a[0] != "12" || a[1] == deCirc || fake.exitOf(a[1]) != fakeRelays[5].fp {
		t.Fatalf("stream 12 re-attached to %v (old circuit %s)", a, deCirc)
	}

	// An exit refusing a port: no other US exit takes 8443, so the stream
	// is closed rather than sent through another country.
	fake.send(fmt.Sprintf(`650 STREAM 11 DETACHED %s example.com:8443 REASON=EXITPOLICY SOCKS_USERNAME="wd-us"`, usCirc))
	if id := waitFor(t, fake.closed, "8443 close"); id != "11" {
		t.Fatalf("closed %s", id)
	}

	s.stop()
	fake.mu.Lock()
	leave := append([]string(nil), fake.leave...)
	fake.mu.Unlock()
	if len(leave) < 2 || leave[0] != "__LeaveStreamsUnattached=1" || leave[len(leave)-1] != "__LeaveStreamsUnattached=0" {
		t.Fatalf("SETCONF sequence = %v", leave)
	}
}

func TestTorParsing(t *testing.T) {
	got := splitArgs(`STREAM 5 NEW 0 a.com:443 SOCKS_USERNAME="wd-us x\"y" PURPOSE=USER`)
	want := []string{"STREAM", "5", "NEW", "0", "a.com:443", `SOCKS_USERNAME=wd-us x"y`, "PURPOSE=USER"}
	if strings.Join(got, "|") != strings.Join(want, "|") {
		t.Fatalf("splitArgs = %q", got)
	}
	pos, kv := splitKV([]string{"$" + strings.Repeat("A", 40) + "=nick", "PURPOSE=GENERAL"})
	if len(pos) != 1 || kv["PURPOSE"] != "GENERAL" {
		t.Fatalf("splitKV = %v %v", pos, kv)
	}
	for _, c := range []struct {
		policy string
		port   int
		want   bool
	}{
		{"accept 80,443", 443, true},
		{"accept 80,443", 22, false},
		{"accept 20-23,43,53,79-81,88,110,143,194,220,389,443,464-465", 22, true},
		{"reject 25,465,587", 443, true},
		{"reject 1-65535", 443, false},
	} {
		if got := policyAllows(c.policy, c.port); got != c.want {
			t.Errorf("policyAllows(%q, %d) = %v", c.policy, c.port, got)
		}
	}
	if k := keyFor("wd-DE"); k != "de" {
		t.Errorf("keyFor = %q", k)
	}
	if k := keyFor("wd-any"); k != "" {
		t.Errorf("keyFor any = %q", k)
	}
	if _, err := newTorUpstream("127.0.0.1:9050", "usa"); err == nil {
		t.Error("accepted a bad country code")
	}
}
