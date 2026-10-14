package heymuse

import (
	"encoding/binary"
	"math"

	"github.com/pgodlews/hey-muse-portal/core/endpoint"
	"github.com/pgodlews/hey-muse-portal/core/wake"
)

// Rate is the microphone rate everything here takes: 16 kHz, 16-bit little-endian, mono.
const Rate = 16000

// What Listen says about an utterance.
const (
	// Listening: the speaker may not have finished.
	Listening = 0
	// Finished: the speaker has stopped. Utterance is what to ask.
	Finished = 1
	// NothingSaid: nobody spoke after the wake word, or the tap.
	NothingSaid = 2
)

const (
	// maxUtterance is the longest question: 60 seconds, the most hey-muse ever sent.
	maxUtterance = 60 * Rate
	// giveUpAfter is how long nothing at all may be said before the turn is dropped.
	giveUpAfter = 6 * Rate
)

// Ears listen for the wake word, and then for the end of what was said.
type Ears struct {
	wake  *wake.Detector
	ep    *endpoint.Detector
	heard []int16
	// floor and peak are the quietest and loudest tenth of a second since Begin, and sum and n
	// the window being filled. The endpoint detector measures the speaker against their own
	// loudest moment, so a room with nobody speaking in it looks to it like speech; set against
	// the room's own floor, it does not.
	floor, peak, sum float64
	n                int
}

// WakePhrase is what the wake word is.
const WakePhrase = wake.Phrase

// NewEars loads the wake word, keeping its model in dir. sensitivity is 0 to 1, where 0.5 is the
// trained default and higher wakes more readily.
func NewEars(dir string, sensitivity float64) (*Ears, error) {
	w, err := wake.New(dir, cutoffFor(sensitivity))
	if err != nil {
		return nil, err
	}
	return &Ears{wake: w}, nil
}

// cutoffFor maps a sensitivity onto the model's cutoff: 0.5 is the trained cutoff, 1 is 0.80, and 0
// is 0.999.
func cutoffFor(sensitivity float64) float64 {
	s := math.Min(math.Max(sensitivity, 0), 1)
	if s >= 0.5 {
		return wake.DefaultCutoff - (s-0.5)*2*(wake.DefaultCutoff-0.80)
	}
	return wake.DefaultCutoff + (0.5-s)*2*(0.999-wake.DefaultCutoff)
}

// Wake takes the next audio while waiting and reports whether the wake word was just heard.
func (e *Ears) Wake(pcm []byte) bool { return e.wake.Feed(samplesOf(pcm)) }

// ResetWake forgets audio heard so far, for audio that does not follow on from the last.
func (e *Ears) ResetWake() { e.wake.Reset() }

// Begin starts listening to an utterance.
func (e *Ears) Begin() {
	e.ep = endpoint.New(endpoint.Patient)
	e.heard = e.heard[:0]
	e.floor, e.peak, e.sum, e.n = math.Inf(1), 0, 0, 0
}

// spoke says whether anything stood out from the room: speech is several times louder than the
// floor it starts from.
func (e *Ears) spoke() bool { return e.peak > 4*e.floor+50 }

// Listen takes the next audio of the utterance and says whether it is over (Listening, Finished
// or NothingSaid).
func (e *Ears) Listen(pcm []byte) int {
	if e.ep == nil {
		e.Begin()
	}
	s := samplesOf(pcm)
	e.heard = append(e.heard, s...)
	for _, v := range s {
		e.sum += float64(v) * float64(v)
		if e.n++; e.n == endpoint.Window {
			rms := math.Sqrt(e.sum / endpoint.Window)
			e.floor, e.peak = math.Min(e.floor, rms), math.Max(e.peak, rms)
			e.sum, e.n = 0, 0
		}
	}
	switch {
	case len(e.heard) >= giveUpAfter && !e.spoke():
		return NothingSaid
	case e.ep.Feed(s):
		if at := e.ep.EndedAt(); at > 0 && at < len(e.heard) {
			e.heard = e.heard[:min(at+3*endpoint.Window, len(e.heard))]
		}
		return Finished
	case len(e.heard) >= maxUtterance:
		return Finished
	}
	return Listening
}

// Utterance is what was heard since Begin, to hand to AskVoice.
func (e *Ears) Utterance() []byte { return bytesOf(e.heard) }

func samplesOf(pcm []byte) []int16 {
	s := make([]int16, len(pcm)/2)
	for i := range s {
		s[i] = int16(binary.LittleEndian.Uint16(pcm[2*i:]))
	}
	return s
}

func bytesOf(samples []int16) []byte {
	b := make([]byte, 2*len(samples))
	for i, v := range samples {
		binary.LittleEndian.PutUint16(b[2*i:], uint16(v))
	}
	return b
}

// wavFile wraps 16 kHz 16-bit mono samples as the voice note Muse takes.
func wavFile(samples []int16) []byte {
	data := 2 * len(samples)
	b := make([]byte, 0, 44+data)
	b = append(b, "RIFF"...)
	b = binary.LittleEndian.AppendUint32(b, uint32(36+data))
	b = append(b, "WAVEfmt "...)
	b = binary.LittleEndian.AppendUint32(b, 16)
	b = binary.LittleEndian.AppendUint16(b, 1) // PCM
	b = binary.LittleEndian.AppendUint16(b, 1) // mono
	b = binary.LittleEndian.AppendUint32(b, Rate)
	b = binary.LittleEndian.AppendUint32(b, Rate*2)
	b = binary.LittleEndian.AppendUint16(b, 2)
	b = binary.LittleEndian.AppendUint16(b, 16)
	b = append(b, "data"...)
	b = binary.LittleEndian.AppendUint32(b, uint32(data))
	return append(b, bytesOf(samples)...)
}

// Speech loudness, from hey-muse's media package: a quiet recording is brought up to -18 dBFS, by
// at most +18 dB, with its peaks soft-limited, so Muse hears a soft-spoken question.
const (
	speechTargetRMS = 0.126
	speechMaxGain   = 8.0
	speechKnee      = 0.8
)

func normalize(in []int16) []int16 {
	if len(in) == 0 {
		return in
	}
	var sum float64
	for _, s := range in {
		v := float64(s) / 32768
		sum += v * v
	}
	rms := math.Sqrt(sum / float64(len(in)))
	if rms <= 0 {
		return in
	}
	gain := math.Min(speechTargetRMS/rms, speechMaxGain)
	if gain <= 1 {
		return in
	}
	out := make([]int16, len(in))
	const knee = speechKnee * 32767
	const headroom = 32767 - knee
	for i, s := range in {
		v := float64(s) * gain
		a := math.Abs(v)
		if a > knee {
			over := a - knee
			a = knee + headroom*over/(over+headroom)
			if v < 0 {
				v = -a
			} else {
				v = a
			}
		}
		out[i] = int16(v)
	}
	return out
}
