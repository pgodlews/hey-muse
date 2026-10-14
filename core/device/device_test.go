package device

import (
	"path/filepath"
	"strings"
	"testing"
	"time"
)

type fakeHost struct {
	volume  int
	rings   []string
	ringing bool
	stopped int
}

func (h *fakeHost) SetVolume(level int) int { h.volume = level; return level }
func (h *fakeHost) Volume() int             { return h.volume }
func (h *fakeHost) Ring(kind, label string) {
	h.rings = append(h.rings, kind+":"+label)
	h.ringing = true
}
func (h *fakeHost) StopRinging() bool { r := h.ringing; h.ringing = false; return r }
func (h *fakeHost) StopSpeaking()     { h.stopped++ }

// clockAt is a device whose clock is *now.
func clockAt(t *testing.T, now *time.Time) (*Device, *fakeHost) {
	t.Helper()
	h := &fakeHost{}
	d, err := New(h, filepath.Join(t.TempDir(), "alarms.json"), nil)
	if err != nil {
		t.Fatal(err)
	}
	d.now = func() time.Time { return *now }
	return d, h
}

func TestTimerRingsOnce(t *testing.T) {
	now := time.Date(2026, 10, 14, 12, 0, 0, 0, time.UTC)
	d, h := clockAt(t, &now)
	d.Check()
	if _, err := d.RunTool("start_timer", map[string]any{"seconds": 90.0, "label": "pasta"}); err != nil {
		t.Fatal(err)
	}
	now = now.Add(89 * time.Second)
	d.Check()
	if len(h.rings) != 0 {
		t.Fatalf("rang early: %v", h.rings)
	}
	now = now.Add(2 * time.Second)
	d.Check()
	d.Check()
	if len(h.rings) != 1 || h.rings[0] != "timer:pasta" {
		t.Fatalf("rings = %v", h.rings)
	}
	if len(d.Timers()) != 0 {
		t.Fatal("timer kept after ringing")
	}
}

func TestCancelTheOnlyTimer(t *testing.T) {
	now := time.Date(2026, 10, 14, 12, 0, 0, 0, time.UTC)
	d, _ := clockAt(t, &now)
	d.StartTimer("eggs", time.Minute)
	got, err := d.RunTool("cancel_timers", map[string]any{"label": "the timer"})
	if err != nil || got != "canceled one timer" {
		t.Fatalf("got %q, %v", got, err)
	}
}

func TestAlarmOnceRingsAndTurnsOff(t *testing.T) {
	now := time.Date(2026, 10, 14, 6, 29, 0, 0, time.UTC)
	d, h := clockAt(t, &now)
	d.Check()
	if _, err := d.RunTool("set_alarm", map[string]any{"time": "6:30"}); err != nil {
		t.Fatal(err)
	}
	now = now.Add(59 * time.Second)
	d.Check()
	now = now.Add(2 * time.Second)
	d.Check()
	if len(h.rings) != 1 {
		t.Fatalf("rings = %v", h.rings)
	}
	if a := d.Alarms(); len(a) != 1 || a[0].On {
		t.Fatalf("alarms = %+v", a)
	}
	now = now.Add(24 * time.Hour)
	d.Check()
	if len(h.rings) != 1 {
		t.Fatalf("a one-off rang again: %v", h.rings)
	}
}

func TestAlarmWeekdaysSkipsWeekend(t *testing.T) {
	// 2026-10-17 is a Saturday.
	now := time.Date(2026, 10, 17, 7, 0, 0, 0, time.UTC)
	d, _ := clockAt(t, &now)
	a, err := d.SetAlarm(7, 30, DaysWeekdays, "")
	if err != nil {
		t.Fatal(err)
	}
	next := a.next(now)
	if next.Weekday() != time.Monday || next.Hour() != 7 || next.Minute() != 30 {
		t.Fatalf("next = %v", next)
	}
}

func TestMissedAlarmIsNotRungHoursLate(t *testing.T) {
	now := time.Date(2026, 10, 14, 6, 0, 0, 0, time.UTC)
	d, h := clockAt(t, &now)
	d.Check()
	if _, err := d.SetAlarm(6, 30, DaysEvery, ""); err != nil {
		t.Fatal(err)
	}
	now = now.Add(6 * time.Hour) // slept through it
	d.Check()
	if len(h.rings) != 0 {
		t.Fatalf("rang late: %v", h.rings)
	}
}

func TestAlarmsSurviveRestart(t *testing.T) {
	path := filepath.Join(t.TempDir(), "alarms.json")
	d, err := New(&fakeHost{}, path, nil)
	if err != nil {
		t.Fatal(err)
	}
	if _, err := d.SetAlarm(7, 0, DaysOnce, "gym"); err != nil {
		t.Fatal(err)
	}
	again, err := New(&fakeHost{}, path, nil)
	if err != nil {
		t.Fatal(err)
	}
	if a := again.Alarms(); len(a) != 1 || a[0].Label != "gym" {
		t.Fatalf("alarms = %+v", a)
	}
	// New ids do not collide with kept ones.
	b, _ := again.SetAlarm(8, 0, DaysOnce, "")
	if b.ID == a(again)[0].ID {
		t.Fatal("id reused")
	}
}

func a(d *Device) []Alarm { return d.Alarms() }

func TestDeleteAlarmByTime(t *testing.T) {
	now := time.Date(2026, 10, 14, 6, 0, 0, 0, time.UTC)
	d, _ := clockAt(t, &now)
	d.SetAlarm(7, 0, DaysOnce, "")
	d.SetAlarm(8, 0, DaysOnce, "")
	got, err := d.RunTool("delete_alarm", map[string]any{"time": "08:00"})
	if err != nil || !strings.HasPrefix(got, "deleted the 08:00") {
		t.Fatalf("got %q, %v", got, err)
	}
	if len(d.Alarms()) != 1 {
		t.Fatal("not deleted")
	}
}

func TestVolumeAndStop(t *testing.T) {
	now := time.Now()
	d, h := clockAt(t, &now)
	got, err := d.RunTool("set_volume", map[string]any{"level": "14"})
	if err != nil || got != "volume is 10 of 10" {
		t.Fatalf("got %q, %v", got, err)
	}
	h.ringing = true
	got, _ = d.RunTool("stop", nil)
	if got != "stopped the ringing" || h.stopped != 1 {
		t.Fatalf("got %q, stopped %d", got, h.stopped)
	}
}

func TestClockReading(t *testing.T) {
	for in, want := range map[string][2]int{
		"6:30": {6, 30}, "6:30 pm": {18, 30}, "18:30": {18, 30}, "7 am": {7, 0}, "12 am": {0, 0}, "2": {14, 0},
	} {
		h, m, err := alarmClock(in)
		if err != nil || h != want[0] || m != want[1] {
			t.Errorf("%q: %d:%d %v", in, h, m, err)
		}
	}
	if _, _, err := alarmClock("13 pm"); err == nil {
		t.Error("13 pm accepted")
	}
}

func TestCommandsAreNamedForThePortal(t *testing.T) {
	now := time.Now()
	d, _ := clockAt(t, &now)
	for _, c := range d.Commands() {
		if !strings.HasPrefix(c.Name, "portal.") || c.Handler == nil || c.Description == "" {
			t.Errorf("command %+v", c)
		}
	}
}
