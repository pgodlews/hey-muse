package heymuse

import (
	"context"
	"strings"
	"sync"

	"github.com/pgodlews/hey-muse-portal/core/speech"
)

// CloudRate is the rate a cloud voice's audio arrives at: 24 kHz, 16-bit little-endian, mono.
const CloudRate = speech.Rate

// CloudVoices are the voices of the default speech model, comma separated.
func CloudVoices() string { return strings.Join(speech.Voices, ",") }

// AudioSink takes a voice's audio as it is made. An error from Write stops the voice.
type AudioSink interface {
	Write(pcm []byte) error
}

// Speech is one answer said by an OpenAI-style /audio/speech endpoint.
type Speech struct {
	c      speech.Client
	mu     sync.Mutex
	cancel context.CancelFunc
	done   bool
}

// NewSpeech is a voice from an endpoint. base and model may be empty for OpenAI's and
// gpt-4o-mini-tts; style is how to say it, for a model that takes directions.
func NewSpeech(base, key, model, voice, style string) *Speech {
	return &Speech{c: speech.Client{Base: base, Key: key, Model: model, Voice: voice, Style: style}}
}

// Say says text into sink at CloudRate, and returns when the voice has ended, failed, or Cancel was
// called. A Speech says one thing.
func (s *Speech) Say(text string, sink AudioSink) error {
	ctx, cancel := context.WithCancel(context.Background())
	s.mu.Lock()
	if s.done {
		s.mu.Unlock()
		cancel()
		return context.Canceled
	}
	s.cancel = cancel
	s.mu.Unlock()
	defer cancel()
	return s.c.Speak(ctx, text, func(samples []int16) error {
		if err := ctx.Err(); err != nil {
			return err
		}
		return sink.Write(bytesOf(samples))
	})
}

// Cancel stops the voice, whether it has started or not.
func (s *Speech) Cancel() {
	s.mu.Lock()
	defer s.mu.Unlock()
	s.done = true
	if s.cancel != nil {
		s.cancel()
	}
}

// Spoken tidies text for a voice.
func Spoken(text string) string { return speech.Spoken(text) }
