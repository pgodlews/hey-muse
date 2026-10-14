package wake

import (
	"encoding/binary"
	"os"
	"testing"
)

func clip(t *testing.T, name string) []int16 {
	t.Helper()
	b, err := os.ReadFile("testdata/" + name)
	if err != nil {
		t.Fatal(err)
	}
	s := make([]int16, 16000, 16000+len(b)/2+16000) // a second of quiet either side
	for i := 0; i+1 < len(b); i += 2 {
		s = append(s, int16(binary.LittleEndian.Uint16(b[i:])))
	}
	return append(s, make([]int16, 16000)...)
}

// heard feeds a clip in the 20 ms reads the app makes and counts the wakes.
func heard(t *testing.T, d *Detector, s []int16) int {
	n := 0
	for i := 0; i+320 <= len(s); i += 320 {
		if d.Feed(s[i : i+320]) {
			n++
		}
	}
	return n
}

func TestWakesOnThePhraseOnce(t *testing.T) {
	d, err := New(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if n := heard(t, d, clip(t, "hey_muse.pcm")); n != 1 {
		t.Fatalf("woke %d times", n)
	}
}

func TestDoesNotWakeOnOtherWords(t *testing.T) {
	d, err := New(t.TempDir(), 0)
	if err != nil {
		t.Fatal(err)
	}
	if n := heard(t, d, clip(t, "hello.pcm")); n != 0 {
		t.Fatalf("woke %d times", n)
	}
}

func TestModelIsRewrittenWhenItDiffers(t *testing.T) {
	dir := t.TempDir()
	if err := os.WriteFile(dir+"/hey_muse.tflite", []byte("stale"), 0o600); err != nil {
		t.Fatal(err)
	}
	if _, err := New(dir, 0); err != nil {
		t.Fatal(err)
	}
}
