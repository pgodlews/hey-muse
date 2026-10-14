package heymuse

import (
	"context"
	"errors"
	"fmt"
	"log/slog"
	"time"

	"github.com/pgodlews/hey-muse-portal/core/muse"
	"github.com/pgodlews/hey-muse-portal/core/muse/pairing"
)

// The GATT shape the app's server has to have. The phone writes commands to RX and subscribes to
// TX for the answers.
const (
	ServiceUUID = pairing.ServiceUUID
	RXUUID      = pairing.RXUUID
	TXUUID      = pairing.TXUUID
	// ManufacturerID and ManufacturerData go in the advertisement: the apps read them as the
	// device's "not yet paired" flag.
	ManufacturerID   = int(pairing.ManufacturerID)
	ManufacturerData = int(pairing.ManufacturerData)
	// AssumedMTU stands in until the phone has negotiated one.
	AssumedMTU = pairing.AssumedMTU
	// PairingWindowSeconds is how long pairing stays open.
	PairingWindowSeconds = int(pairing.DefaultWindow / time.Second)
)

// Peripheral is the app's Bluetooth LE GATT server, which the Muse app pairs over.
//
// Open puts it on the air: advertising the service UUID, with ManufacturerData under ManufacturerID,
// and name in the scan response; serving the service with RX (write, write without response) and TX
// (read, notify, with its client configuration descriptor). Each write to RX goes to
// App.PairingWrite whole, a phone dropping goes to App.PairingDisconnected, and neither is called
// from inside SendPacket or Disconnect.
type Peripheral interface {
	Open(name string) error
	// SendPacket notifies one packet on TX to the connected phone, and returns once the stack has taken it.
	SendPacket(packet []byte) error
	// MTU is the negotiated ATT MTU, or AssumedMTU before there is one.
	MTU() int
	// Disconnect drops the phone.
	Disconnect() error
	// Close takes the advertisement and the server down. It is called once, after the pairing ends.
	Close()
}

type openPairing struct {
	ctrl   *pairing.Controller
	cancel context.CancelFunc
	done   chan struct{}
}

// transport is the Peripheral as the Controller drives it.
type transport struct{ p Peripheral }

func (t transport) Send(packets [][]byte) error {
	for i, packet := range packets {
		if i > 0 {
			time.Sleep(pairing.ChunkStagger)
		}
		if err := t.p.SendPacket(packet); err != nil {
			return err
		}
	}
	return nil
}

func (t transport) MTU() int          { return t.p.MTU() }
func (t transport) Disconnect() error { return t.p.Disconnect() }

// StartPairing opens the Portal to the Muse app for PairingWindowSeconds, or until CancelPairing.
// ssid is the Wi-Fi the Portal is on, for the app to show; empty is fine. The connection to Muse
// stops meanwhile: one identity holds one session, and its tokens are about to be replaced.
func (a *App) StartPairing(p Peripheral, ssid string) error {
	if p == nil {
		return errors.New("heymuse: a peripheral is required")
	}
	a.mu.Lock()
	switch {
	case a.ctx == nil:
		a.mu.Unlock()
		return errors.New("not running")
	case a.pair != nil:
		a.mu.Unlock()
		return nil
	case a.sdkToken == "":
		a.mu.Unlock()
		return errors.New("add your Muse SDK token first: the Muse app will not pair without one")
	}
	// The identity is kept before it is advertised: the app remembers the device by it.
	if !a.st.Identity.Valid() {
		id, err := muse.NewIdentity()
		if err == nil {
			st := a.st
			st.Identity = id
			err = a.saveLocked(st)
		}
		if err != nil {
			a.mu.Unlock()
			return fmt.Errorf("could not keep the Portal's identity: %w", err)
		}
	}
	id := a.st.Identity
	ctx, cancel := context.WithCancel(a.ctx)
	ctrl, err := pairing.New(pairing.Config{
		Device:    pairing.Device{NodeID: id.NodeID(), BLEName: id.BLEName(), MAC: id.MAC, Version: a.version},
		Transport: transport{p},
		SDKToken:  a.sdkToken,
		Network:   pairing.AlreadyOnline{SSID: func() string { return ssid }},
		Save:      a.savePairing,
		Progress:  a.progressed,
	})
	if err != nil {
		cancel()
		a.mu.Unlock()
		return err
	}
	op := &openPairing{ctrl: ctrl, cancel: cancel, done: make(chan struct{})}
	a.pair = op
	a.progress = &pairing.Progress{State: pairing.StateWaiting}
	a.err = nil
	a.reconcileLocked()
	a.mu.Unlock()

	// Opening the peripheral calls into the app, so it is done without the lock held.
	if err := p.Open(id.BLEName()); err != nil {
		cancel()
		a.mu.Lock()
		a.pair = nil
		a.progress = nil
		a.reconcileLocked()
		a.mu.Unlock()
		close(op.done)
		a.changed()
		return fmt.Errorf("could not advertise to the Muse app: %w", err)
	}
	slog.Info("muse pairing: open", "name", id.BLEName(), "for", pairing.DefaultWindow)
	a.changed()

	go func() {
		defer close(op.done)
		err := ctrl.Run(ctx)
		if err == nil {
			// The phone is still reading auth_ok.
			time.Sleep(pairing.ShutdownGrace)
		}
		p.Close()
		cancel()
		a.mu.Lock()
		if a.pair == op {
			a.pair = nil
		}
		a.reconcileLocked()
		a.mu.Unlock()
		if err != nil {
			slog.Info("muse pairing: closed", "err", err)
		}
		a.changed()
	}()
	return nil
}

// PairingWrite is the phone writing to RX.
func (a *App) PairingWrite(data []byte) {
	if ctrl := a.controller(); ctrl != nil {
		ctrl.OnWrite(data)
	}
}

// PairingDisconnected is the phone dropping.
func (a *App) PairingDisconnected() {
	if ctrl := a.controller(); ctrl != nil {
		ctrl.OnDisconnect()
	}
}

func (a *App) controller() *pairing.Controller {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.pair == nil {
		return nil
	}
	return a.pair.ctrl
}

// CancelPairing takes the Portal off the air.
func (a *App) CancelPairing() {
	a.mu.Lock()
	defer a.mu.Unlock()
	if a.pair != nil {
		a.pair.cancel()
	}
}

// savePairing keeps the tokens the app sent, before the phone is told auth_ok.
func (a *App) savePairing(r pairing.Result) error {
	a.mu.Lock()
	defer a.mu.Unlock()
	st := a.st
	st.Credentials = &muse.Credentials{
		AccessToken:        r.AccessToken,
		RefreshToken:       r.RefreshToken,
		TokenType:          r.TokenType,
		Username:           r.Username,
		APIURL:             r.APIURL,
		APIURLV2:           r.APIURLV2,
		NoiseHost:          r.NoiseHost,
		AccessTokenSavedAt: time.Now().Unix(),
	}
	st.SDKTokenReported = false
	return a.saveLocked(st)
}

func (a *App) progressed(p pairing.Progress) {
	a.mu.Lock()
	a.progress = &p
	a.mu.Unlock()
	a.changed()
}
