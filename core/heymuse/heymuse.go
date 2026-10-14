// Package heymuse is what the Android app calls: the Portal as a Muse gadget. It is bound into the
// APK with gomobile (scripts/build-core.sh), so everything exported here keeps to the types gomobile
// can carry: strings, numbers, bools, byte slices, and interfaces the app implements.
//
// The app owns the screen, the microphone, the speaker and Bluetooth. This package owns the rest:
// the pairing with the Muse app (as a conversation over a GATT server the app runs), the connection
// to Muse, each question and its answer, what Muse may do on the Portal (core/device), the wake word
// and the end of an utterance (Ears), and the cloud voice (Speech).
//
// Callbacks into the app arrive on goroutines of their own, never the app's main thread, and must
// not block for long.
package heymuse

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"io/fs"
	"log/slog"
	"path/filepath"
	"sync"
	"time"

	"github.com/pgodlews/hey-muse/core/device"
	"github.com/pgodlews/hey-muse/core/muse"
	"github.com/pgodlews/hey-muse/core/muse/pairing"
)

// Host is the app, as the core sees it.
type Host interface {
	// OnStatus says where the Portal stands with Muse, as Status JSON. It is sent on every change.
	OnStatus(status string)
	// OnHeard is what Muse made of the voice note of turn.
	OnHeard(turn int, text string)
	// OnAnswer is Muse's answer to turn, tidied for a voice. It is the turn's last event.
	OnAnswer(turn int, text string)
	// OnTurnFailed is why turn got no answer, in words to say. It is the turn's last event.
	OnTurnFailed(turn int, reason string)
	// OnTurnEnded is turn ending with nothing to say. It is the turn's last event.
	OnTurnEnded(turn int)
	// OnDeviceChanged says the timers or alarms changed.
	OnDeviceChanged()

	// SetVolume sets the speaker, 0 to VolumeSteps, and returns where it ended up.
	SetVolume(level int) int
	// Volume is the speaker now, 0 to VolumeSteps.
	Volume() int
	// Ring starts the sound of a timer ("timer") or an alarm ("alarm") going off.
	Ring(kind, label string)
	// StopRinging silences a ring and says whether there was one.
	StopRinging() bool
	// StopSpeaking cuts off an answer being spoken.
	StopSpeaking()

	// Log is one line for logcat. level is "debug", "info", "warn" or "error".
	Log(level, line string)
}

// VolumeSteps is the top of the volume scale Muse uses.
const VolumeSteps = device.VolumeSteps

// The phases of Status.
const (
	PhaseUnpaired   = "unpaired"
	PhasePairing    = "pairing"
	PhaseConnecting = "connecting"
	PhaseOnline     = "online"
	PhaseOffline    = "offline"
	PhaseStopped    = "stopped"
)

// Status is what the screen shows about Muse. It reaches the app as JSON.
type Status struct {
	Phase string `json:"phase"`
	// Username is who the Portal is paired as, once it is.
	Username string `json:"username,omitempty"`
	// BLEName is what the Muse app lists the Portal as while pairing.
	BLEName string `json:"bleName,omitempty"`
	// Error is why the Portal is offline, or what went wrong. Never a token.
	Error string `json:"error,omitempty"`
	// Pairing is how far the last pairing came.
	Pairing *PairingStatus `json:"pairing,omitempty"`
	// HasSDKToken is whether a token has been given, which pairing needs.
	HasSDKToken bool `json:"hasSdkToken"`
}

// PairingStatus is one step of a pairing.
type PairingStatus struct {
	State  string `json:"state"`
	Reason string `json:"reason,omitempty"`
	Error  string `json:"error,omitempty"`
	Done   bool   `json:"done"`
	Failed bool   `json:"failed"`
}

// App is the Portal as a Muse gadget. Make one with NewApp, Start it, and Stop it when the service
// goes.
type App struct {
	host    Host
	store   muse.FileStore
	version string
	dev     *device.Device

	mu          sync.Mutex
	ctx         context.Context
	stopRun     context.CancelFunc
	st          muse.State
	phase       string
	err         error
	progress    *pairing.Progress
	sdkToken    string
	displayName string
	client      *running
	pair        *openPairing
	turnID      int
	turn        *activeTurn
}

type running struct {
	sdkToken    string
	displayName string
	c           *muse.Client
	stop        context.CancelFunc
	stopping    bool
	done        chan struct{}
}

// NewApp makes the app's core, keeping its files in dir: the pairing in muse.json and the alarms
// in alarms.json. version is what Muse is told the software is.
func NewApp(dir, displayName, version string, host Host) (*App, error) {
	if host == nil {
		return nil, errors.New("heymuse: a host is required")
	}
	slog.SetDefault(slog.New(&hostLog{host: host}))
	a := &App{
		host:        host,
		store:       muse.FileStore{Path: filepath.Join(dir, "muse.json")},
		version:     version,
		displayName: displayName,
		phase:       PhaseStopped,
	}
	dev, err := device.New(deviceHost{host}, filepath.Join(dir, "alarms.json"), host.OnDeviceChanged)
	if err != nil {
		return nil, err
	}
	a.dev = dev
	return a, nil
}

// deviceHost is the Host as core/device sees it.
type deviceHost struct{ h Host }

func (d deviceHost) SetVolume(level int) int { return d.h.SetVolume(level) }
func (d deviceHost) Volume() int             { return d.h.Volume() }
func (d deviceHost) Ring(kind, label string) { d.h.Ring(kind, label) }
func (d deviceHost) StopRinging() bool       { return d.h.StopRinging() }
func (d deviceHost) StopSpeaking()           { d.h.StopSpeaking() }

// Start reads the pairing, connects to Muse when the Portal is paired, and starts the timers and
// alarms. Starting an app that is running does nothing.
func (a *App) Start() {
	a.mu.Lock()
	if a.ctx != nil {
		a.mu.Unlock()
		return
	}
	a.ctx, a.stopRun = context.WithCancel(context.Background())
	ctx := a.ctx
	st, err := a.store.Load()
	switch {
	case err == nil:
		a.st = st
	case errors.Is(err, fs.ErrNotExist):
	default:
		slog.Error("muse: could not read the pairing", "err", err)
		a.err = err
	}
	a.reconcileLocked()
	a.mu.Unlock()
	go a.dev.Run(ctx)
	a.changed()
}

// Stop takes everything down and returns once the connection and any pairing are gone.
func (a *App) Stop() {
	a.mu.Lock()
	if a.ctx == nil {
		a.mu.Unlock()
		return
	}
	a.stopRun()
	a.ctx = nil
	pair, client := a.pair, a.client
	if pair != nil {
		pair.cancel()
	}
	if a.turn != nil {
		a.turn.cancel()
	}
	a.reconcileLocked()
	a.phase = PhaseStopped
	a.mu.Unlock()
	if pair != nil {
		<-pair.done
	}
	if client != nil {
		<-client.done
	}
	a.changed()
}

// SetSDKToken is the mgst_ token from gadgets.muse.ai. Pairing needs it, and the connection
// reports it to Muse once. Empty takes it away.
func (a *App) SetSDKToken(token string) {
	a.mu.Lock()
	a.sdkToken = token
	a.reconcileLocked()
	a.mu.Unlock()
	a.changed()
}

// SetDisplayName is what the Muse app calls the Portal. It takes effect on the next connection.
func (a *App) SetDisplayName(name string) {
	a.mu.Lock()
	a.displayName = name
	a.reconcileLocked()
	a.mu.Unlock()
}

// Paired is whether the Muse app has paired the Portal.
func (a *App) Paired() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.st.Credentials != nil
}

// Online is whether a question can be asked now.
func (a *App) Online() bool {
	a.mu.Lock()
	defer a.mu.Unlock()
	return a.phase == PhaseOnline
}

// Status is Status as JSON.
func (a *App) Status() string {
	a.mu.Lock()
	s := a.statusLocked()
	a.mu.Unlock()
	b, _ := json.Marshal(s)
	return string(b)
}

func (a *App) statusLocked() Status {
	s := Status{Phase: a.phase, HasSDKToken: a.sdkToken != ""}
	if a.err != nil {
		s.Error = a.err.Error()
	}
	if a.st.Credentials != nil {
		s.Username = a.st.Credentials.Username
	}
	if a.st.Identity.Valid() {
		s.BLEName = a.st.Identity.BLEName()
	}
	if p := a.progress; p != nil {
		ps := &PairingStatus{
			State:  p.State.String(),
			Done:   p.State == pairing.StateDone,
			Failed: p.State == pairing.StateFailed,
		}
		if p.Reason != pairing.ReasonNone {
			ps.Reason = p.Reason.String()
		}
		if p.Err != nil {
			ps.Error = p.Err.Error()
		}
		s.Pairing = ps
	}
	return s
}

func (a *App) changed() { a.host.OnStatus(a.Status()) }

// reconcileLocked makes what runs match what should: a client exactly when the app is started, the
// Portal is paired and no pairing is open, built from the current settings. Every change ends here.
func (a *App) reconcileLocked() {
	want := a.ctx != nil && a.st.Credentials != nil && a.pair == nil
	if r := a.client; r != nil && !r.stopping && (!want || r.sdkToken != a.sdkToken || r.displayName != a.displayName) {
		r.stopping = true
		r.stop()
	}
	if want && a.client == nil {
		a.startLocked()
	}
	switch {
	case a.ctx == nil:
		a.phase = PhaseStopped
	case a.pair != nil:
		a.phase = PhasePairing
	case a.st.Credentials == nil:
		a.phase = PhaseUnpaired
	}
}

// startLocked brings a client up, and has it reconciled away when its Run returns: stopped, unpaired
// by Muse, or given up.
func (a *App) startLocked() {
	ctx, cancel := context.WithCancel(a.ctx)
	r := &running{sdkToken: a.sdkToken, displayName: a.displayName, stop: cancel, done: make(chan struct{})}
	c, err := muse.New(muse.Config{
		Store:       stateStore{a: a, r: r},
		Commands:    a.dev.Commands(),
		DisplayName: a.displayName,
		Version:     a.version,
		SDKToken:    a.sdkToken,
		Logger:      slog.Default(),
		OnState:     func(s muse.ConnState) { a.connState(r, s) },
	})
	if err != nil {
		cancel()
		slog.Error("muse: could not make the client", "err", err)
		a.err = err
		return
	}
	r.c = c
	a.client = r
	a.phase, a.err = PhaseConnecting, nil
	go func() {
		defer close(r.done)
		err := c.Run(ctx)
		a.mu.Lock()
		if a.client == r {
			a.client = nil
		}
		if err != nil && !errors.Is(err, context.Canceled) && !errors.Is(err, muse.ErrUnpaired) {
			a.err = err
		}
		a.reconcileLocked()
		if a.client == nil && a.phase == PhaseConnecting {
			a.phase = PhaseOffline
		}
		a.mu.Unlock()
		a.changed()
	}()
}

func (a *App) connState(r *running, s muse.ConnState) {
	a.mu.Lock()
	if a.client != r {
		a.mu.Unlock()
		return
	}
	switch s.Phase {
	case muse.Connecting:
		a.phase, a.err = PhaseConnecting, nil
	case muse.Online:
		a.phase, a.err = PhaseOnline, nil
	case muse.Offline:
		a.phase, a.err = PhaseOffline, s.Err
	}
	a.mu.Unlock()
	a.changed()
}

func (a *App) saveLocked(st muse.State) error {
	if err := a.store.Save(st); err != nil {
		return err
	}
	a.st = st
	return nil
}

// stateStore is the client's view of the file. A save from a client on its way out is refused, so
// a token rotation racing an Unpair cannot bring the credentials back.
type stateStore struct {
	a *App
	r *running
}

func (s stateStore) Load() (muse.State, error) { return s.a.store.Load() }

func (s stateStore) Save(st muse.State) error {
	s.a.mu.Lock()
	if s.a.client != s.r || s.r.stopping {
		s.a.mu.Unlock()
		return errors.New("muse: the client is being stopped")
	}
	err := s.a.saveLocked(st)
	if err == nil {
		s.a.reconcileLocked()
	}
	s.a.mu.Unlock()
	if err == nil {
		s.a.changed()
	}
	return err
}

// Unpair forgets the tokens and keeps the identity, so the Portal pairs again as itself. Muse is not
// told: the device list in the Muse app is where the owner removes it.
func (a *App) Unpair() error {
	a.mu.Lock()
	if a.pair != nil {
		a.pair.cancel()
	}
	err := a.saveLocked(muse.State{Identity: a.st.Identity})
	if err == nil {
		a.err = nil
		a.progress = nil
		a.reconcileLocked()
	}
	a.mu.Unlock()
	if err != nil {
		return fmt.Errorf("could not forget the pairing: %w", err)
	}
	slog.Info("muse: unpaired")
	a.changed()
	return nil
}

// Timers are the timers running, as JSON: [{"id","label","end" (Unix milliseconds)}].
func (a *App) Timers() string {
	type t struct {
		ID    int    `json:"id"`
		Label string `json:"label"`
		End   int64  `json:"end"`
	}
	out := []t{}
	for _, x := range a.dev.Timers() {
		out = append(out, t{x.ID, x.Label, x.End.UnixMilli()})
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// Alarms are the alarms set, as JSON: [{"id","hour","minute","days" (words),"label","on","next"
// (Unix milliseconds)}].
func (a *App) Alarms() string {
	type al struct {
		ID     int    `json:"id"`
		Hour   int    `json:"hour"`
		Minute int    `json:"minute"`
		Days   string `json:"days"`
		Label  string `json:"label"`
		On     bool   `json:"on"`
		Next   int64  `json:"next"`
	}
	out := []al{}
	for _, x := range a.dev.Alarms() {
		out = append(out, al{x.ID, x.Hour, x.Minute, device.DaysLabel(x.Days), x.Label, x.On, a.dev.Next(x).UnixMilli()})
	}
	b, _ := json.Marshal(out)
	return string(b)
}

// CancelTimer stops a timer by its id.
func (a *App) CancelTimer(id int) { a.dev.CancelTimer(id) }

// DeleteAlarm forgets an alarm by its id.
func (a *App) DeleteAlarm(id int) error {
	_, err := a.dev.DeleteAlarm(id)
	return err
}

// StartTimer starts a timer from the screen.
func (a *App) StartTimer(seconds int, label string) {
	a.dev.StartTimer(label, time.Duration(seconds)*time.Second)
}

// SetAlarm sets an alarm from the screen. days is once, daily, weekdays, weekends or a list of days.
func (a *App) SetAlarm(hour, minute int, days, label string) error {
	d, err := device.ParseDays(days)
	if err != nil {
		return err
	}
	if hour < 0 || hour > 23 || minute < 0 || minute > 59 {
		return errors.New("not a time of day")
	}
	_, err = a.dev.SetAlarm(hour, minute, d, label)
	return err
}
