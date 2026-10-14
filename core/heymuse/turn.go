package heymuse

import (
	"context"
	"errors"
	"log/slog"
	"strings"
	"time"

	"github.com/pgodlews/hey-muse/core/endpoint"
	"github.com/pgodlews/hey-muse/core/muse"
	"github.com/pgodlews/hey-muse/core/speech"
)

// activeTurn is the question that is open.
type activeTurn struct {
	id     int
	cancel context.CancelFunc
	done   chan struct{}
}

// AskVoice asks Muse what was said in pcm, 16 kHz 16-bit little-endian mono, and returns the turn's
// number. The answer arrives through the Host: OnHeard, then exactly one of OnAnswer, OnTurnFailed or
// OnTurnEnded, unless the turn is canceled first, which ends it with nothing more said.
//
// A turn still open is canceled first: its answer has been said, or nobody is waiting for it.
func (a *App) AskVoice(pcm []byte) int {
	samples := samplesOf(pcm)
	wav := wavFile(normalize(endpoint.Trim(samples)))
	return a.ask(muse.VoiceNote(wav))
}

// AskText asks Muse a typed question, the same way as AskVoice.
func (a *App) AskText(text string) int {
	return a.ask(muse.Text(text))
}

// CancelTurn ends the open turn, if there is one, with nothing more said.
func (a *App) CancelTurn() {
	a.mu.Lock()
	t := a.turn
	a.mu.Unlock()
	if t != nil {
		t.cancel()
		<-t.done
	}
}

func (a *App) ask(in muse.AskInput) int {
	a.CancelTurn()
	a.mu.Lock()
	a.turnID++
	id := a.turnID
	var c *muse.Client
	if a.client != nil && !a.client.stopping {
		c = a.client.c
	}
	paired := a.st.Credentials != nil
	pairingOpen := a.pair != nil
	ctx, cancel := context.WithCancel(context.Background())
	if a.ctx != nil {
		ctx, cancel = context.WithCancel(a.ctx)
	}
	t := &activeTurn{id: id, cancel: cancel, done: make(chan struct{})}
	a.turn = t
	a.mu.Unlock()

	go func() {
		defer close(t.done)
		defer cancel()
		defer func() {
			a.mu.Lock()
			if a.turn == t {
				a.turn = nil
			}
			a.mu.Unlock()
		}()
		switch {
		case pairingOpen:
			a.host.OnTurnFailed(id, "I'm pairing with the Muse app right now.")
			return
		case !paired:
			a.host.OnTurnFailed(id, "This Portal is not paired with Muse yet.")
			return
		case c == nil:
			a.host.OnTurnFailed(id, "I can't reach Muse right now.")
			return
		}
		a.answer(ctx, id, c, in)
	}()
	return id
}

// answer asks and reports. The first Settled is taken as the whole answer and handed over at once:
// Muse going on after a pause is rare, and an answer that waited for it would start late every time.
func (a *App) answer(ctx context.Context, id int, c *muse.Client, in muse.AskInput) {
	start := time.Now()
	var answered bool
	reply, err := c.Ask(ctx, in, func(e muse.ReplyEvent) {
		if ctx.Err() != nil || answered {
			return
		}
		switch e := e.(type) {
		case muse.Heard:
			a.host.OnHeard(id, strings.TrimSpace(e.Text))
		case muse.Settled:
			answered = true
			slog.Info("muse turn answered", "ms", time.Since(start).Milliseconds())
			a.host.OnAnswer(id, speech.Spoken(e.Text))
		}
	})
	switch {
	case answered || ctx.Err() != nil:
	case err != nil:
		slog.Warn("muse turn failed", "err", err)
		a.host.OnTurnFailed(id, excuse(err))
	default:
		if text := speech.Spoken(reply.Text); text != "" {
			a.host.OnAnswer(id, text)
		} else {
			a.host.OnTurnEnded(id)
		}
	}
}

// excuse is a failure in words to say: a tone alone does not tell somebody whether to say it again,
// wait, or go and pair the Portal.
func excuse(err error) string {
	switch {
	case errors.Is(err, muse.ErrOffline):
		return "I lost the connection to Muse. Try again in a moment."
	case errors.Is(err, muse.ErrBusy):
		return "Muse is still answering the last question."
	}
	return "Muse didn't answer that. Try again."
}
