package heymuse

import (
	"encoding/binary"
	"encoding/json"
	"os"
	"strings"
	"sync"
	"testing"
	"time"
)

// fakeHost records what the core tells the app.
type fakeHost struct {
	mu       sync.Mutex
	statuses []Status
	events   chan string
	volume   int
}

func newHost() *fakeHost { return &fakeHost{events: make(chan string, 64)} }

func (h *fakeHost) OnStatus(s string) {
	var st Status
	if err := json.Unmarshal([]byte(s), &st); err != nil {
		panic(err)
	}
	h.mu.Lock()
	h.statuses = append(h.statuses, st)
	h.mu.Unlock()
}
func (h *fakeHost) last() Status {
	h.mu.Lock()
	defer h.mu.Unlock()
	return h.statuses[len(h.statuses)-1]
}
func (h *fakeHost) OnHeard(turn int, text string)        { h.events <- "heard:" + text }
func (h *fakeHost) OnAnswer(turn int, text string)       { h.events <- "answer:" + text }
func (h *fakeHost) OnTurnFailed(turn int, reason string) { h.events <- "failed:" + reason }
func (h *fakeHost) OnTurnEnded(turn int)                 { h.events <- "ended" }
func (h *fakeHost) OnDeviceChanged()                     {}
func (h *fakeHost) SetVolume(level int) int              { h.volume = level; return level }
func (h *fakeHost) Volume() int                          { return h.volume }
func (h *fakeHost) Ring(kind, label string)              {}
func (h *fakeHost) StopRinging() bool                    { return false }
func (h *fakeHost) StopSpeaking()                        {}
func (h *fakeHost) Log(level, line string)               {}

func (h *fakeHost) next(t *testing.T) string {
	t.Helper()
	select {
	case e := <-h.events:
		return e
	case <-time.After(5 * time.Second):
		t.Fatal("no event")
		return ""
	}
}

func TestUnpairedTurnSaysSo(t *testing.T) {
	h := newHost()
	a, err := NewApp(t.TempDir(), "Portal", "test", h)
	if err != nil {
		t.Fatal(err)
	}
	a.Start()
	defer a.Stop()
	if got := h.last().Phase; got != PhaseUnpaired {
		t.Fatalf("phase = %q", got)
	}
	a.AskText("hello")
	if got := h.next(t); !strings.HasPrefix(got, "failed:") || !strings.Contains(got, "not paired") {
		t.Fatalf("event = %q", got)
	}
}

func TestStopSaysStopped(t *testing.T) {
	h := newHost()
	a, _ := NewApp(t.TempDir(), "Portal", "test", h)
	a.Start()
	a.Stop()
	if got := h.last().Phase; got != PhaseStopped {
		t.Fatalf("phase = %q", got)
	}
}

// fakePeripheral is a GATT server nobody connects to.
type fakePeripheral struct {
	mu     sync.Mutex
	name   string
	closed chan struct{}
}

func (p *fakePeripheral) Open(name string) error {
	p.mu.Lock()
	p.name = name
	p.mu.Unlock()
	return nil
}
func (p *fakePeripheral) SendPacket([]byte) error { return nil }
func (p *fakePeripheral) MTU() int                { return AssumedMTU }
func (p *fakePeripheral) Disconnect() error       { return nil }
func (p *fakePeripheral) Close()                  { close(p.closed) }

func TestPairingNeedsTheSDKToken(t *testing.T) {
	h := newHost()
	a, _ := NewApp(t.TempDir(), "Portal", "test", h)
	a.Start()
	defer a.Stop()
	if err := a.StartPairing(&fakePeripheral{closed: make(chan struct{})}, ""); err == nil || !strings.Contains(err.Error(), "SDK token") {
		t.Fatalf("err = %v", err)
	}
}

func TestPairingOpensUnderTheIdentityAndCancels(t *testing.T) {
	dir := t.TempDir()
	h := newHost()
	a, _ := NewApp(dir, "Portal", "test", h)
	a.Start()
	defer a.Stop()
	a.SetSDKToken("mgst_test")
	p := &fakePeripheral{closed: make(chan struct{})}
	if err := a.StartPairing(p, "HomeNet"); err != nil {
		t.Fatal(err)
	}
	st := h.last()
	if st.Phase != PhasePairing || !strings.HasPrefix(st.BLEName, "MuseGadget") || p.name != st.BLEName {
		t.Fatalf("status = %+v, advertised %q", st, p.name)
	}
	// The identity is on disk before it is advertised.
	b, err := os.ReadFile(dir + "/muse.json")
	if err != nil || !strings.Contains(string(b), "identity") {
		t.Fatalf("muse.json = %s, %v", b, err)
	}
	a.AskText("hello")
	if got := h.next(t); !strings.Contains(got, "pairing") {
		t.Fatalf("event = %q", got)
	}
	a.CancelPairing()
	select {
	case <-p.closed:
	case <-time.After(5 * time.Second):
		t.Fatal("peripheral not closed")
	}
	deadline := time.Now().Add(5 * time.Second)
	for h.last().Phase != PhaseUnpaired {
		if time.Now().After(deadline) {
			t.Fatalf("status = %+v", h.last())
		}
		time.Sleep(10 * time.Millisecond)
	}
	// Pairing again keeps the same name.
	name := h.last().BLEName
	p2 := &fakePeripheral{closed: make(chan struct{})}
	if err := a.StartPairing(p2, ""); err != nil {
		t.Fatal(err)
	}
	if p2.name != name {
		t.Fatalf("advertised %q after %q", p2.name, name)
	}
	a.CancelPairing()
	<-p2.closed
}

func pcmClip(t *testing.T, name string) []byte {
	t.Helper()
	b, err := os.ReadFile("../wake/testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	return b
}

func TestEarsWakeThenHearTheQuestionOut(t *testing.T) {
	e, err := NewEars(t.TempDir(), 0.5)
	if err != nil {
		t.Fatal(err)
	}
	quiet := make([]byte, 640)
	for i := 0; i < 50; i++ {
		if e.Wake(quiet) {
			t.Fatal("woke on silence")
		}
	}
	clip := append(pcmClip(t, "hey_muse.pcm"), make([]byte, 32000)...)
	woke := false
	for i := 0; i+640 <= len(clip); i += 640 {
		if e.Wake(clip[i : i+640]) {
			woke = true
			break
		}
	}
	if !woke {
		t.Fatal("did not wake")
	}

	// A question, then quiet with a little room noise.
	e.Begin()
	question := pcmClip(t, "hello.pcm")
	noise := make([]byte, 2*Rate*3)
	for i := 0; i < len(noise); i += 2 {
		binary.LittleEndian.PutUint16(noise[i:], uint16(int16((i*7919)%61-30)))
	}
	audio := append(question, noise...)
	got := Listening
	n := 0
	for ; n+640 <= len(audio) && got == Listening; n += 640 {
		got = e.Listen(audio[n : n+640])
	}
	if got != Finished {
		t.Fatalf("Listen = %d", got)
	}
	if n < len(question) {
		t.Fatalf("ended at %d of a %d-byte question", n, len(question))
	}
	if u := e.Utterance(); len(u) < len(question)/2 || len(u) > len(audio) {
		t.Fatalf("utterance is %d bytes", len(u))
	}
}

func TestEarsGiveUpWhenNothingIsSaid(t *testing.T) {
	e, _ := NewEars(t.TempDir(), 0.5)
	e.Begin()
	quiet := make([]byte, 640)
	for i := 0; i < 2*giveUpAfter/320; i++ {
		switch e.Listen(quiet) {
		case NothingSaid:
			return
		case Finished:
			t.Fatal("finished on silence")
		}
	}
	t.Fatal("never gave up")
}

func TestWAVHeader(t *testing.T) {
	w := wavFile(make([]int16, 100))
	if len(w) != 244 || string(w[:4]) != "RIFF" || string(w[8:12]) != "WAVE" || binary.LittleEndian.Uint32(w[24:]) != Rate {
		t.Fatalf("header % x", w[:44])
	}
}

func TestSensitivityMapsAroundTheTrainedCutoff(t *testing.T) {
	if c := cutoffFor(0.5); c < 0.98 || c > 0.995 {
		t.Fatalf("0.5 -> %v", c)
	}
	if !(cutoffFor(1) < cutoffFor(0.5) && cutoffFor(0.5) < cutoffFor(0)) {
		t.Fatal("not monotonic")
	}
	if c := cutoffFor(1); c < 0.79 || c > 0.81 {
		t.Fatalf("1 -> %v", c)
	}
}

func TestEarsGiveUpOnSteadyRoomNoise(t *testing.T) {
	e, _ := NewEars(t.TempDir(), 0.5)
	e.Begin()
	noise := make([]byte, 640)
	for i := 0; i < len(noise); i += 2 {
		binary.LittleEndian.PutUint16(noise[i:], uint16(int16((i*7919)%401-200)))
	}
	for i := 0; i < 2*giveUpAfter/320; i++ {
		switch e.Listen(noise) {
		case NothingSaid:
			return
		case Finished:
			t.Fatal("finished on room noise")
		}
	}
	t.Fatal("never gave up")
}
