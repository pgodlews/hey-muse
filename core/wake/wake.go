// Package wake listens for "Hey Muse": the microWakeWord model trained for hey-muse (see
// tools/wake/README.md), run by zserge/microwakeword, which is pure Go.
package wake

import (
	"bytes"
	_ "embed"
	"encoding/json"
	"os"
	"path/filepath"

	"github.com/zserge/microwakeword"
)

//go:embed hey_muse.tflite
var model []byte

//go:embed hey_muse.json
var manifest []byte

// Phrase is what the model listens for.
const Phrase = "Hey Muse"

// DefaultCutoff is the cutoff the trainer's calibration chose. Lower wakes more readily, and falsely
// more often.
var DefaultCutoff, window, step = func() (float64, int, int) {
	var m struct {
		Micro struct {
			ProbabilityCutoff float64 `json:"probability_cutoff"`
			SlidingWindowSize int     `json:"sliding_window_size"`
			FeatureStepSize   int     `json:"feature_step_size"`
		} `json:"micro"`
	}
	if err := json.Unmarshal(manifest, &m); err != nil {
		panic(err)
	}
	return m.Micro.ProbabilityCutoff, m.Micro.SlidingWindowSize, m.Micro.FeatureStepSize
}()

// Detector follows 16 kHz mono audio and says when the phrase was heard.
type Detector struct{ d *microwakeword.Detector }

// New writes the model into dir, where the engine reads it from, and loads it with the cutoff given;
// zero is DefaultCutoff.
func New(dir string, cutoff float64) (*Detector, error) {
	if cutoff <= 0 || cutoff >= 1 {
		cutoff = DefaultCutoff
	}
	path := filepath.Join(dir, "hey_muse.tflite")
	if have, err := os.ReadFile(path); err != nil || !bytes.Equal(have, model) {
		if err := os.MkdirAll(dir, 0o700); err != nil {
			return nil, err
		}
		if err := os.WriteFile(path, model, 0o600); err != nil {
			return nil, err
		}
	}
	d, err := microwakeword.NewDetector(microwakeword.Config{
		ModelPath:         path,
		ProbabilityCutoff: cutoff,
		SlidingWindowSize: window,
		FeaturesStepMs:    step,
	})
	if err != nil {
		return nil, err
	}
	return &Detector{d: d}, nil
}

// Feed takes the next samples and reports whether the phrase was just heard. After it has, the
// detector starts afresh, so one phrase wakes it once.
func (w *Detector) Feed(samples []int16) bool {
	if w.d.ProcessAudio(samples) {
		w.d.Reset()
		return true
	}
	return false
}

// Reset forgets what was heard so far, for audio that does not follow on from the last.
func (w *Detector) Reset() { w.d.Reset() }
