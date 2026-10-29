// SPDX-License-Identifier: GPL-3.0-or-later

package vpncore

// Per-app Tor exit countries.
//
// Tor chooses exits for everything it carries from one global setting
// (ExitNodes), so it can't send one web app out through Germany and another
// through Japan by itself. WebDroid therefore acts as Tor's controller for
// the streams it opens: with __LeaveStreamsUnattached set, every new stream
// waits for us, and we attach it to a circuit we built through an exit in
// the country its app asked for. Each Tor upstream tags its SOCKS username
// with its country ("wd-de"), which Tor reports in its STREAM events.
//
// Circuits keep Tor's own entry guard; only the middle and exit relays are
// chosen here (weighted by bandwidth, never two in the same /16). Streams
// for apps that don't care about the country go to Tor's own circuits.
// Steering only runs while some app asks for a country.

import (
	"bufio"
	"context"
	"encoding/base64"
	"encoding/hex"
	"errors"
	"fmt"
	"io"
	"math/rand/v2"
	"net"
	"os"
	"sort"
	"strconv"
	"strings"
	"sync"
	"time"
)

// torCircuitLifetime matches Tor's MaxCircuitDirtiness: after this long a
// circuit takes no new streams, so exits rotate as they would normally.
const torCircuitLifetime = 10 * time.Minute

var errNoExits = errors.New("no Tor exits")

var steering struct {
	mu     sync.Mutex
	socket string // Tor's ControlSocket
	geo    string // Tor's IPv4 GeoIP file
	s      *torSteer
	wanted bool // some upstream asks for an exit country
}

// SetTorControl tells the core where Tor's control socket and its GeoIP
// database are. Call it once Tor is running, before SetTorUpstream.
func SetTorControl(socketPath, geoIPPath string) {
	steering.mu.Lock()
	defer steering.mu.Unlock()
	if steering.socket != socketPath && steering.s != nil {
		steering.s.stop()
		steering.s = nil
	}
	steering.socket, steering.geo = socketPath, geoIPPath
}

// SetTorUpstream configures upstream id as Tor's SOCKS port at socksAddr,
// leaving the Tor network in country (a two-letter code, or "" for any).
func SetTorUpstream(id, socksAddr, country string) error {
	rt, err := current()
	if err != nil {
		return err
	}
	u, err := newTorUpstream(socksAddr, country)
	if err != nil {
		return err
	}
	rt.setUpstream(id, u)
	updateSteering(rt)
	return nil
}

// TorSteering returns "" when exit countries work, or what's wrong.
func TorSteering() string {
	steering.mu.Lock()
	defer steering.mu.Unlock()
	if !steering.wanted {
		return ""
	}
	if steering.s == nil {
		return "not started"
	}
	select {
	case <-steering.s.ctl.done:
		return steering.s.ctl.err.Error()
	default:
		return ""
	}
}

// updateSteering stops steering once no upstream asks for a country (it
// starts on the first connection that needs it).
func updateSteering(rt *router) {
	want := false
	rt.mu.RLock()
	for _, u := range rt.upstreams {
		if t, ok := u.(*torUpstream); ok && t.country != "" {
			want = true
			break
		}
	}
	rt.mu.RUnlock()
	steering.mu.Lock()
	defer steering.mu.Unlock()
	steering.wanted = want
	if !want && steering.s != nil {
		steering.s.stop()
		steering.s = nil
	}
}

func ensureSteering() error {
	steering.mu.Lock()
	defer steering.mu.Unlock()
	if s := steering.s; s != nil {
		select {
		case <-s.ctl.done:
		default:
			return nil
		}
	}
	if steering.socket == "" {
		return errors.New("Tor isn't running yet")
	}
	geo, err := loadGeoIPCached(steering.geo)
	if err != nil {
		return err
	}
	s, err := startTorSteer(steering.socket, geo)
	if err != nil {
		return err
	}
	steering.s = s
	return nil
}

func stopSteering() {
	steering.mu.Lock()
	defer steering.mu.Unlock()
	if steering.s != nil {
		steering.s.stop()
		steering.s = nil
	}
	steering.wanted = false
}

func steeringProblem(country string) string {
	steering.mu.Lock()
	s := steering.s
	steering.mu.Unlock()
	if s == nil {
		return ""
	}
	return s.problem(country)
}

/* -------------------------------------------------------------------------- */
/* Upstream                                                                    */
/* -------------------------------------------------------------------------- */

// torUpstream is Tor's SOCKS port, for streams leaving in one country.
type torUpstream struct {
	proxy   *proxyUpstream
	country string // "" for anywhere
}

func newTorUpstream(addr, country string) (*torUpstream, error) {
	country = strings.ToLower(strings.TrimSpace(country))
	if country != "" && (len(country) != 2 || country[0] < 'a' || country[0] > 'z' || country[1] < 'a' || country[1] > 'z') {
		return nil, fmt.Errorf("%q is not a country code", country)
	}
	tag := country
	if tag == "" {
		tag = "any"
	}
	// The username tags the stream (and isolates countries from each other).
	p, err := newProxy("socks5", "wd-"+tag+":webdroid@"+addr)
	if err != nil {
		return nil, err
	}
	return &torUpstream{proxy: p, country: country}, nil
}

func (t *torUpstream) Dial(ctx context.Context, network, addr string) (net.Conn, error) {
	steering.mu.Lock()
	wanted := steering.wanted
	steering.mu.Unlock()
	if t.country != "" || wanted {
		// While countries are steered every Tor stream waits for the
		// controller, so make sure it's there (it restarts if Tor dropped it).
		if err := ensureSteering(); err != nil && t.country != "" {
			err = fmt.Errorf("couldn't pick a Tor exit in %s: %w", strings.ToUpper(t.country), err)
			t.proxy.note(err)
			return nil, err
		}
	}
	return t.proxy.Dial(ctx, network, addr)
}

func (t *torUpstream) Close() {}

func (t *torUpstream) spec() string { return "tor|" + t.country + "|" + t.proxy.addr }

func (t *torUpstream) Status() map[string]any {
	st := t.proxy.Status()
	st["type"] = "tor"
	st["country"] = t.country
	if t.country != "" {
		if p := steeringProblem(t.country); p != "" {
			st["state"] = "error"
			st["error"] = p
		}
	}
	return st
}

/* -------------------------------------------------------------------------- */
/* Control connection                                                          */
/* -------------------------------------------------------------------------- */

type ctlLine struct {
	code string
	text string
	data []string // a "+" line's data block
}

// torControl speaks Tor's control protocol: one command at a time, with
// asynchronous events queued (without limit, so a slow handler can never
// hold up the replies it is waiting for).
type torControl struct {
	conn    net.Conn
	cmdMu   sync.Mutex
	replies chan []ctlLine
	done    chan struct{}
	once    sync.Once
	err     error

	evMu    sync.Mutex
	evQueue [][]ctlLine
	events  chan struct{}
}

func newTorControl(conn net.Conn) *torControl {
	c := &torControl{
		conn:    conn,
		replies: make(chan []ctlLine, 1),
		done:    make(chan struct{}),
		events:  make(chan struct{}, 1),
	}
	go c.read()
	return c
}

func (c *torControl) fail(err error) {
	c.once.Do(func() {
		c.err = err
		close(c.done)
		_ = c.conn.Close()
	})
}

func readCtlLine(br *bufio.Reader) (string, error) {
	line, err := br.ReadString('\n')
	if err != nil {
		return "", err
	}
	return strings.TrimRight(line, "\r\n"), nil
}

func (c *torControl) read() {
	br := bufio.NewReaderSize(c.conn, 64<<10)
	var reply, event []ctlLine
	for {
		line, err := readCtlLine(br)
		if err != nil {
			c.fail(fmt.Errorf("Tor's control connection closed: %w", err))
			return
		}
		if len(line) < 4 {
			continue
		}
		l := ctlLine{code: line[:3], text: line[4:]}
		sep := line[3]
		if sep == '+' {
			l.data = []string{}
			for {
				d, err := readCtlLine(br)
				if err != nil {
					c.fail(err)
					return
				}
				if d == "." {
					break
				}
				if strings.HasPrefix(d, "..") {
					d = d[1:]
				}
				l.data = append(l.data, d)
			}
		}
		if l.code == "650" {
			event = append(event, l)
			if sep == ' ' {
				c.evMu.Lock()
				c.evQueue = append(c.evQueue, event)
				c.evMu.Unlock()
				select {
				case c.events <- struct{}{}:
				default:
				}
				event = nil
			}
			continue
		}
		reply = append(reply, l)
		if sep == ' ' {
			select {
			case c.replies <- reply:
			case <-c.done:
				return
			}
			reply = nil
		}
	}
}

func (c *torControl) takeEvents() [][]ctlLine {
	c.evMu.Lock()
	defer c.evMu.Unlock()
	q := c.evQueue
	c.evQueue = nil
	return q
}

// command sends one command and returns its reply; a non-2xx reply is an error.
func (c *torControl) command(cmd string) ([]ctlLine, error) {
	c.cmdMu.Lock()
	defer c.cmdMu.Unlock()
	select {
	case <-c.done:
		return nil, c.err
	default:
	}
	_ = c.conn.SetWriteDeadline(time.Now().Add(10 * time.Second))
	if _, err := io.WriteString(c.conn, cmd+"\r\n"); err != nil {
		c.fail(err)
		return nil, err
	}
	timer := time.NewTimer(60 * time.Second)
	defer timer.Stop()
	select {
	case r := <-c.replies:
		last := r[len(r)-1]
		if !strings.HasPrefix(last.code, "2") {
			return r, fmt.Errorf("%s %s", last.code, last.text)
		}
		return r, nil
	case <-c.done:
		return nil, c.err
	case <-timer.C:
		c.fail(errors.New("Tor stopped answering"))
		return nil, c.err
	}
}

// getInfo returns the values of keys (a data block's lines joined by "\n").
func (c *torControl) getInfo(keys ...string) (map[string]string, error) {
	r, err := c.command("GETINFO " + strings.Join(keys, " "))
	if err != nil {
		return nil, err
	}
	out := map[string]string{}
	for _, l := range r {
		k, v, ok := strings.Cut(l.text, "=")
		if !ok {
			continue
		}
		if l.data != nil {
			v = strings.Join(l.data, "\n")
		}
		out[k] = v
	}
	return out, nil
}

// splitArgs splits a control-protocol line on spaces, keeping quoted
// strings together and unescaping them (KEY="a b" becomes KEY=a b).
func splitArgs(s string) []string {
	var out []string
	var b strings.Builder
	inQuote, escaped, quoted := false, false, false
	for i := 0; i < len(s); i++ {
		ch := s[i]
		switch {
		case escaped:
			b.WriteByte(ch)
			escaped = false
		case inQuote && ch == '\\':
			escaped = true
		case ch == '"':
			inQuote = !inQuote
			quoted = true
		case ch == ' ' && !inQuote:
			if b.Len() > 0 || quoted {
				out = append(out, b.String())
			}
			b.Reset()
			quoted = false
		default:
			b.WriteByte(ch)
		}
	}
	if b.Len() > 0 || quoted {
		out = append(out, b.String())
	}
	return out
}

// splitKV separates positional arguments from KEY=value ones.
func splitKV(tokens []string) (pos []string, kv map[string]string) {
	kv = map[string]string{}
	for _, t := range tokens {
		k, v, ok := strings.Cut(t, "=")
		if ok && k != "" && strings.Trim(k, "ABCDEFGHIJKLMNOPQRSTUVWXYZ_") == "" {
			kv[k] = v
		} else {
			pos = append(pos, t)
		}
	}
	return pos, kv
}

// relayIDs parses a path like "$FP~nick,$FP=nick" into fingerprints.
func relayIDs(path string) []string {
	var out []string
	for _, hop := range strings.Split(path, ",") {
		hop = strings.TrimPrefix(hop, "$")
		if i := strings.IndexAny(hop, "~="); i >= 0 {
			hop = hop[:i]
		}
		if len(hop) == 40 {
			out = append(out, strings.ToUpper(hop))
		}
	}
	return out
}

/* -------------------------------------------------------------------------- */
/* Relays, GeoIP and exit policies                                             */
/* -------------------------------------------------------------------------- */

type torRelay struct {
	fp                                         string
	ip                                         net.IP
	bw                                         int64
	cc                                         string
	exit, badExit, fast, guard, running, valid bool
}

// parseNetworkStatus reads GETINFO ns/all.
func parseNetworkStatus(text string, geo *geoIP) map[string]*torRelay {
	out := map[string]*torRelay{}
	var cur *torRelay
	for _, line := range strings.Split(text, "\n") {
		f := strings.Fields(line)
		if len(f) == 0 {
			continue
		}
		switch f[0] {
		case "r":
			cur = nil
			if len(f) < 7 {
				continue
			}
			id, err := base64.RawStdEncoding.DecodeString(strings.TrimRight(f[2], "="))
			if err != nil || len(id) != 20 {
				continue
			}
			r := &torRelay{fp: strings.ToUpper(hex.EncodeToString(id))}
			for _, tok := range f[3:] {
				if ip := net.ParseIP(tok); ip != nil && ip.To4() != nil {
					r.ip = ip.To4()
					break
				}
			}
			if r.ip == nil {
				continue
			}
			if geo != nil {
				r.cc = geo.country(r.ip)
			}
			out[r.fp] = r
			cur = r
		case "s":
			if cur == nil {
				continue
			}
			for _, flag := range f[1:] {
				switch flag {
				case "Exit":
					cur.exit = true
				case "BadExit":
					cur.badExit = true
				case "Fast":
					cur.fast = true
				case "Guard":
					cur.guard = true
				case "Running":
					cur.running = true
				case "Valid":
					cur.valid = true
				}
			}
		case "w":
			if cur == nil {
				continue
			}
			for _, kv := range f[1:] {
				if v, ok := strings.CutPrefix(kv, "Bandwidth="); ok {
					cur.bw, _ = strconv.ParseInt(v, 10, 64)
				}
			}
		}
	}
	return out
}

// policyAllows evaluates an exit policy summary ("accept 80,443,1000-2000"
// or "reject 25,465") for a port.
func policyAllows(summary string, port int) bool {
	action, list, ok := strings.Cut(strings.TrimSpace(summary), " ")
	if !ok {
		return true
	}
	listed := false
	for _, part := range strings.Split(list, ",") {
		lo, hi, isRange := strings.Cut(part, "-")
		a, err := strconv.Atoi(lo)
		if err != nil {
			continue
		}
		b := a
		if isRange {
			if b, err = strconv.Atoi(hi); err != nil {
				continue
			}
		}
		if port >= a && port <= b {
			listed = true
			break
		}
	}
	if action == "accept" {
		return listed
	}
	return !listed
}

type geoIP struct {
	starts, ends []uint32
	cc           []string
}

var geoCache struct {
	mu   sync.Mutex
	path string
	db   *geoIP
}

func loadGeoIPCached(path string) (*geoIP, error) {
	geoCache.mu.Lock()
	defer geoCache.mu.Unlock()
	if geoCache.db != nil && geoCache.path == path {
		return geoCache.db, nil
	}
	db, err := loadGeoIP(path)
	if err != nil {
		return nil, err
	}
	geoCache.path, geoCache.db = path, db
	return db, nil
}

// loadGeoIP reads Tor's geoip file: "FROM,TO,CC" lines where the addresses
// are 32-bit integers (or dotted quads).
func loadGeoIP(path string) (*geoIP, error) {
	if path == "" {
		return nil, errors.New("Tor's country database isn't installed")
	}
	f, err := os.Open(path)
	if err != nil {
		return nil, fmt.Errorf("Tor's country database: %w", err)
	}
	defer f.Close()
	g := &geoIP{}
	sc := bufio.NewScanner(f)
	for sc.Scan() {
		line := strings.TrimSpace(sc.Text())
		if line == "" || line[0] == '#' {
			continue
		}
		parts := strings.Split(line, ",")
		if len(parts) != 3 {
			continue
		}
		lo, ok1 := parseIPv4Number(parts[0])
		hi, ok2 := parseIPv4Number(parts[1])
		if !ok1 || !ok2 || hi < lo {
			continue
		}
		g.starts = append(g.starts, lo)
		g.ends = append(g.ends, hi)
		g.cc = append(g.cc, strings.ToLower(strings.TrimSpace(parts[2])))
	}
	if err := sc.Err(); err != nil {
		return nil, err
	}
	if len(g.starts) == 0 {
		return nil, errors.New("Tor's country database is empty")
	}
	if !sort.SliceIsSorted(g.starts, func(i, j int) bool { return g.starts[i] < g.starts[j] }) {
		idx := make([]int, len(g.starts))
		for i := range idx {
			idx[i] = i
		}
		sort.Slice(idx, func(a, b int) bool { return g.starts[idx[a]] < g.starts[idx[b]] })
		s, e, c := make([]uint32, len(idx)), make([]uint32, len(idx)), make([]string, len(idx))
		for i, j := range idx {
			s[i], e[i], c[i] = g.starts[j], g.ends[j], g.cc[j]
		}
		g.starts, g.ends, g.cc = s, e, c
	}
	return g, nil
}

func parseIPv4Number(s string) (uint32, bool) {
	s = strings.TrimSpace(s)
	if strings.Contains(s, ".") {
		ip := net.ParseIP(s).To4()
		if ip == nil {
			return 0, false
		}
		return uint32(ip[0])<<24 | uint32(ip[1])<<16 | uint32(ip[2])<<8 | uint32(ip[3]), true
	}
	n, err := strconv.ParseUint(s, 10, 32)
	return uint32(n), err == nil
}

func (g *geoIP) country(ip net.IP) string {
	v4 := ip.To4()
	if v4 == nil {
		return ""
	}
	n := uint32(v4[0])<<24 | uint32(v4[1])<<16 | uint32(v4[2])<<8 | uint32(v4[3])
	i := sort.Search(len(g.starts), func(i int) bool { return g.starts[i] > n }) - 1
	if i >= 0 && n <= g.ends[i] {
		return g.cc[i]
	}
	return ""
}

func sameSlash16(a, b net.IP) bool {
	a, b = a.To4(), b.To4()
	return a != nil && b != nil && a[0] == b[0] && a[1] == b[1]
}

func weightedPick(relays []*torRelay) int {
	var total int64
	for _, r := range relays {
		total += max(r.bw, 1)
	}
	if total <= 0 {
		return rand.IntN(len(relays))
	}
	n := rand.Int64N(total)
	for i, r := range relays {
		n -= max(r.bw, 1)
		if n < 0 {
			return i
		}
	}
	return len(relays) - 1
}

/* -------------------------------------------------------------------------- */
/* Steering                                                                    */
/* -------------------------------------------------------------------------- */

type torCircuit struct {
	id       string
	key      string // exit country it was built for; "" for anywhere (and Tor's own)
	ours     bool
	path     []string
	built    bool
	general  bool
	internal bool
	firstUse time.Time
	refused  map[int]bool // ports its exit turned away
}

func (c *torCircuit) exit() string {
	if len(c.path) == 0 {
		return ""
	}
	return c.path[len(c.path)-1]
}

type waitingStream struct {
	id    string
	port  int
	avoid string // circuit that just failed this stream
}

type torSteer struct {
	ctl *torControl
	geo *geoIP

	// Touched only by the event loop (and by startTorSteer before it runs).
	relays      map[string]*torRelay
	relaysAt    time.Time
	policies    map[string]string
	circs       map[string]*torCircuit
	waiting     map[string][]waitingStream
	building    map[string]string
	keys        map[string]string // stream id -> country
	refusedExit map[string]bool   // "FP:port"
	failures    map[string]int
	nextTry     map[string]time.Time

	stopReq chan chan struct{}

	mu       sync.Mutex
	problems map[string]string // country -> what's wrong, for Status
}

func startTorSteer(socket string, geo *geoIP) (*torSteer, error) {
	conn, err := net.DialTimeout("unix", socket, 5*time.Second)
	if err != nil {
		return nil, fmt.Errorf("couldn't reach Tor's controller: %w", err)
	}
	s := &torSteer{
		ctl:         newTorControl(conn),
		geo:         geo,
		policies:    map[string]string{},
		circs:       map[string]*torCircuit{},
		waiting:     map[string][]waitingStream{},
		building:    map[string]string{},
		keys:        map[string]string{},
		refusedExit: map[string]bool{},
		failures:    map[string]int{},
		nextTry:     map[string]time.Time{},
		stopReq:     make(chan chan struct{}),
		problems:    map[string]string{},
	}
	steps := []string{"AUTHENTICATE", "SETEVENTS CIRC STREAM"}
	for _, cmd := range steps {
		if _, err := s.ctl.command(cmd); err != nil {
			s.ctl.fail(err)
			return nil, fmt.Errorf("Tor's controller: %w", err)
		}
	}
	if info, err := s.ctl.getInfo("circuit-status"); err == nil {
		for _, line := range strings.Split(info["circuit-status"], "\n") {
			if line = strings.TrimSpace(line); line != "" {
				s.onCirc(splitArgs(line))
			}
		}
	}
	if _, err := s.ctl.command("SETCONF __LeaveStreamsUnattached=1"); err != nil {
		s.ctl.fail(err)
		return nil, fmt.Errorf("Tor's controller: %w", err)
	}
	go s.loop()
	return s, nil
}

func (s *torSteer) stop() {
	done := make(chan struct{})
	select {
	case s.stopReq <- done:
		select {
		case <-done:
		case <-time.After(5 * time.Second):
		}
	case <-s.ctl.done:
	case <-time.After(5 * time.Second):
	}
	s.ctl.fail(errors.New("stopped"))
}

func (s *torSteer) loop() {
	tick := time.NewTicker(2 * time.Second)
	defer tick.Stop()
	for {
		select {
		case <-s.ctl.done:
			return
		case done := <-s.stopReq:
			// Hand stream attachment back to Tor; streams still waiting for
			// a country circuit are closed (the page shows an error) rather
			// than sent out somewhere else.
			_, _ = s.ctl.command("SETCONF __LeaveStreamsUnattached=0")
			for key := range s.waiting {
				s.closeWaiting(key)
			}
			close(done)
			return
		case <-s.ctl.events:
			for _, ev := range s.ctl.takeEvents() {
				s.onEvent(ev)
			}
		case <-tick.C:
			s.retry()
		}
	}
}

func (s *torSteer) onEvent(ev []ctlLine) {
	tok := splitArgs(ev[0].text)
	if len(tok) == 0 {
		return
	}
	switch tok[0] {
	case "CIRC":
		s.onCirc(tok[1:])
	case "STREAM":
		s.onStream(tok[1:])
	}
}

// onCirc handles "ID STATUS [PATH] [KEY=VALUE...]" (a CIRC event or a
// circuit-status line).
func (s *torSteer) onCirc(tok []string) {
	if len(tok) < 2 {
		return
	}
	id, status := tok[0], tok[1]
	pos, kv := splitKV(tok[2:])
	c := s.circs[id]
	if c == nil {
		c = &torCircuit{id: id, general: true, refused: map[int]bool{}}
		s.circs[id] = c
	}
	if len(pos) > 0 {
		if p := relayIDs(pos[0]); len(p) > 0 {
			c.path = p
		}
	}
	if p, ok := kv["PURPOSE"]; ok {
		c.general = p == "GENERAL"
	}
	if f, ok := kv["BUILD_FLAGS"]; ok {
		c.internal = strings.Contains(f, "IS_INTERNAL") || strings.Contains(f, "ONEHOP_TUNNEL")
	}
	switch status {
	case "BUILT":
		c.built = true
		if c.ours && s.building[c.key] == id {
			delete(s.building, c.key)
			s.failures[c.key] = 0
			delete(s.nextTry, c.key)
			s.setProblem(c.key, "")
		}
		s.flush(c.key)
	case "FAILED", "CLOSED":
		delete(s.circs, id)
		if c.ours && s.building[c.key] == id {
			delete(s.building, c.key)
			s.backoff(c.key)
			if len(s.waiting[c.key]) > 0 && s.failures[c.key] < 3 {
				s.build(c.key, s.waiting[c.key][0].port)
			}
		}
	}
}

// onStream handles "ID STATUS CIRCUIT TARGET [KEY=VALUE...]".
func (s *torSteer) onStream(tok []string) {
	if len(tok) < 4 {
		return
	}
	sid, status, circ, target := tok[0], tok[1], tok[2], tok[3]
	_, kv := splitKV(tok[4:])
	port := portOf(target)
	switch status {
	case "NEW", "NEWRESOLVE":
		if p := kv["PURPOSE"]; p != "" && p != "USER" {
			return
		}
		key := keyFor(kv["SOCKS_USERNAME"])
		s.keys[sid] = key
		s.place(sid, key, port, "")
	case "DETACHED":
		key, ok := s.keys[sid]
		if !ok {
			key = keyFor(kv["SOCKS_USERNAME"])
			s.keys[sid] = key
		}
		if c := s.circs[circ]; c != nil && kv["REASON"] == "EXITPOLICY" {
			c.refused[port] = true
			if exit := c.exit(); exit != "" {
				s.refusedExit[exit+":"+strconv.Itoa(port)] = true
			}
		}
		s.place(sid, key, port, circ)
	case "SUCCEEDED":
		if key, ok := s.keys[sid]; ok {
			s.setProblem(key, "")
		}
	case "FAILED", "CLOSED":
		delete(s.keys, sid)
		s.dropWaiting(sid)
	}
}

func keyFor(user string) string {
	if k, ok := strings.CutPrefix(user, "wd-"); ok && k != "any" {
		return strings.ToLower(k)
	}
	return ""
}

func portOf(target string) int {
	if i := strings.LastIndexByte(target, ':'); i >= 0 {
		if p, err := strconv.Atoi(target[i+1:]); err == nil {
			return p
		}
	}
	return 443
}

// place attaches a stream to a suitable circuit, or parks it until one is built.
func (s *torSteer) place(sid, key string, port int, avoid string) {
	if c := s.pick(key, port, avoid); c != nil && s.attach(sid, c) {
		return
	}
	s.waiting[key] = append(s.waiting[key], waitingStream{id: sid, port: port, avoid: avoid})
	if s.building[key] == "" && !time.Now().Before(s.nextTry[key]) {
		s.build(key, port)
	}
}

// pick returns a built circuit for the country, preferring the one already
// in use so a site keeps seeing the same address.
func (s *torSteer) pick(key string, port int, avoid string) *torCircuit {
	var best *torCircuit
	now := time.Now()
	for _, c := range s.circs {
		if !c.built || !c.general || c.internal || c.id == avoid || c.key != key || c.refused[port] || len(c.path) < 2 {
			continue
		}
		if key != "" && !c.ours {
			continue
		}
		if !c.firstUse.IsZero() && now.Sub(c.firstUse) > torCircuitLifetime {
			continue
		}
		if best == nil || c.firstUse.After(best.firstUse) {
			best = c
		}
	}
	return best
}

// attach reports false when the circuit can't take the stream.
func (s *torSteer) attach(sid string, c *torCircuit) bool {
	_, err := s.ctl.command("ATTACHSTREAM " + sid + " " + c.id)
	if err == nil {
		if c.firstUse.IsZero() {
			c.firstUse = time.Now()
		}
		return true
	}
	msg := strings.ToLower(err.Error())
	switch {
	case strings.Contains(msg, "unknown circuit"):
		delete(s.circs, c.id)
		return false
	case strings.HasPrefix(msg, "551"):
		c.built = false
		return false
	}
	// The stream is gone or no longer waiting for us: nothing to do.
	return true
}

func (s *torSteer) flush(key string) {
	list := s.waiting[key]
	if len(list) == 0 {
		return
	}
	delete(s.waiting, key)
	for _, w := range list {
		s.place(w.id, key, w.port, w.avoid)
	}
}

func (s *torSteer) retry() {
	keys := make([]string, 0, len(s.waiting))
	for k, list := range s.waiting {
		if len(list) > 0 {
			keys = append(keys, k)
		}
	}
	now := time.Now()
	for _, key := range keys {
		if s.building[key] != "" || now.Before(s.nextTry[key]) {
			continue
		}
		list := s.waiting[key]
		if c := s.pick(key, list[0].port, ""); c != nil {
			s.flush(key)
			continue
		}
		s.build(key, list[0].port)
	}
}

func (s *torSteer) backoff(key string) {
	s.failures[key]++
	delay := time.Duration(1<<min(s.failures[key], 5)) * time.Second
	s.nextTry[key] = time.Now().Add(delay)
}

func (s *torSteer) build(key string, port int) {
	cmd := "EXTENDCIRCUIT 0 purpose=general" // Tor picks the path for "anywhere"
	if key != "" {
		path, err := s.choosePath(key, port)
		if err != nil {
			s.setProblem(key, err.Error())
			s.backoff(key)
			if errors.Is(err, errNoExits) {
				s.closeWaiting(key)
			}
			return
		}
		cmd = "EXTENDCIRCUIT 0 " + strings.Join(path, ",") + " purpose=general"
	}
	r, err := s.ctl.command(cmd)
	if err != nil {
		s.setProblem(key, "Tor couldn't build a circuit: "+err.Error())
		s.backoff(key)
		return
	}
	f := strings.Fields(r[len(r)-1].text)
	if len(f) < 2 || f[0] != "EXTENDED" {
		s.backoff(key)
		return
	}
	c := s.circs[f[1]]
	if c == nil {
		c = &torCircuit{id: f[1], general: true, refused: map[int]bool{}}
		s.circs[f[1]] = c
	}
	c.ours, c.key = true, key
	s.building[key] = f[1]
}

func (s *torSteer) closeWaiting(key string) {
	for _, w := range s.waiting[key] {
		_, _ = s.ctl.command("CLOSESTREAM " + w.id + " 1")
		delete(s.keys, w.id)
	}
	delete(s.waiting, key)
}

func (s *torSteer) dropWaiting(sid string) {
	for key, list := range s.waiting {
		for i, w := range list {
			if w.id == sid {
				s.waiting[key] = append(list[:i:i], list[i+1:]...)
				return
			}
		}
	}
}

func (s *torSteer) loadRelays() error {
	if len(s.relays) > 0 && time.Since(s.relaysAt) < time.Hour {
		return nil
	}
	info, err := s.ctl.getInfo("ns/all")
	if err != nil {
		return fmt.Errorf("Tor's relay list: %w", err)
	}
	relays := parseNetworkStatus(info["ns/all"], s.geo)
	if len(relays) == 0 {
		return errors.New("Tor is still downloading its list of relays")
	}
	s.relays, s.relaysAt = relays, time.Now()
	s.policies = map[string]string{}
	return nil
}

// guard is the entry guard Tor is using (the first hop of its circuits).
func (s *torSteer) guard() string {
	count := map[string]int{}
	for _, c := range s.circs {
		if c.built && !c.ours && len(c.path) >= 3 {
			count[c.path[0]]++
		}
	}
	best, n := "", 0
	for fp, k := range count {
		if k > n || (k == n && fp < best) {
			best, n = fp, k
		}
	}
	if best != "" {
		return best
	}
	if info, err := s.ctl.getInfo("entry-guards"); err == nil {
		for _, line := range strings.Split(info["entry-guards"], "\n") {
			f := strings.Fields(line)
			if len(f) >= 2 && f[1] == "up" {
				if ids := relayIDs(f[0]); len(ids) == 1 {
					return ids[0]
				}
			}
		}
	}
	return ""
}

func (s *torSteer) exitAllows(fp string, port int) bool {
	policy, ok := s.policies[fp]
	if !ok {
		key := "md/id/" + fp
		if info, err := s.ctl.getInfo(key); err == nil {
			for _, line := range strings.Split(info[key], "\n") {
				if p, ok := strings.CutPrefix(line, "p "); ok {
					policy = p
				}
			}
		}
		s.policies[fp] = policy
	}
	return policy == "" || policyAllows(policy, port)
}

// choosePath picks guard, middle and exit for a circuit leaving in country cc.
func (s *torSteer) choosePath(cc string, port int) ([]string, error) {
	if err := s.loadRelays(); err != nil {
		return nil, err
	}
	guard := s.guard()
	if guard == "" {
		return nil, errors.New("Tor is still connecting")
	}
	g := s.relays[guard]
	var exits []*torRelay
	for _, r := range s.relays {
		if r.cc != cc || !r.exit || r.badExit || !r.running || !r.valid || r.fp == guard ||
			s.refusedExit[r.fp+":"+strconv.Itoa(port)] || (g != nil && sameSlash16(r.ip, g.ip)) {
			continue
		}
		exits = append(exits, r)
	}
	for len(exits) > 0 {
		i := weightedPick(exits)
		exit := exits[i]
		exits = append(exits[:i:i], exits[i+1:]...)
		if !s.exitAllows(exit.fp, port) {
			continue
		}
		var middles []*torRelay
		for _, r := range s.relays {
			if !r.running || !r.valid || !r.fast || r.fp == guard || r.fp == exit.fp ||
				sameSlash16(r.ip, exit.ip) || (g != nil && sameSlash16(r.ip, g.ip)) {
				continue
			}
			middles = append(middles, r)
		}
		if len(middles) == 0 {
			return nil, errors.New("Tor has too few relays to build through")
		}
		middle := middles[weightedPick(middles)]
		return []string{"$" + guard, "$" + middle.fp, "$" + exit.fp}, nil
	}
	return nil, fmt.Errorf("%w in %s right now", errNoExits, strings.ToUpper(cc))
}

func (s *torSteer) setProblem(key, msg string) {
	if key == "" {
		return
	}
	s.mu.Lock()
	defer s.mu.Unlock()
	if msg == "" {
		delete(s.problems, key)
	} else {
		s.problems[key] = msg
	}
}

func (s *torSteer) problem(key string) string {
	s.mu.Lock()
	defer s.mu.Unlock()
	return s.problems[key]
}
