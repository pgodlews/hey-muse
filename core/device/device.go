// Package device is what Muse may do on the Portal itself: timers, alarms, the volume, and stopping
// whatever is ringing. It keeps the timers in memory and the alarms in a file, decides when either
// goes off, and leaves the sound and the volume to the Host, which is the Android side.
//
// The command table and the argument handling are from hey-muse's echod/internal/feature/assistant,
// narrowed to what an app on the Portal can do. Nothing here reaches another device, the web or a
// music library.
package device

import (
	"context"
	"encoding/json"
	"errors"
	"fmt"
	"math"
	"os"
	"path/filepath"
	"slices"
	"strconv"
	"strings"
	"sync"
	"time"

	"github.com/pgodlews/hey-muse/core/muse"
)

// VolumeSteps is the top of the volume scale Muse is told about. The Host maps it onto the stream's
// own range.
const VolumeSteps = 10

// Host is the Android side: the speaker and the ringing.
type Host interface {
	// SetVolume sets the volume, 0 to VolumeSteps, and returns where it ended up.
	SetVolume(level int) int
	// Volume is the volume now, 0 to VolumeSteps.
	Volume() int
	// Ring starts a timer's or an alarm's sound. kind is "timer" or "alarm".
	Ring(kind, label string)
	// StopRinging silences a ring, and says whether there was one.
	StopRinging() bool
	// StopSpeaking cuts off an answer being spoken.
	StopSpeaking()
}

// Timer is a countdown that is running.
type Timer struct {
	ID    int       `json:"id"`
	Label string    `json:"label"`
	End   time.Time `json:"end"`
}

// Days is which weekdays an alarm repeats on, bit 0 Sunday through bit 6 Saturday; none is once.
type Days uint8

const (
	DaysOnce     Days = 0
	DaysEvery    Days = 0x7f
	DaysWeekdays Days = 0x3e
	DaysWeekends Days = 0x41
)

var dayNames = [7]string{"sun", "mon", "tue", "wed", "thu", "fri", "sat"}

// Alarm is a time of day to ring at.
type Alarm struct {
	ID     int    `json:"id"`
	Hour   int    `json:"hour"`
	Minute int    `json:"minute"`
	Days   Days   `json:"days,omitempty"`
	Label  string `json:"label,omitempty"`
	On     bool   `json:"on"`
}

// Device keeps the timers and alarms and offers the commands.
type Device struct {
	host Host
	path string
	now  func() time.Time

	mu      sync.Mutex
	nextID  int
	timers  []Timer
	alarms  []Alarm
	checked time.Time
	// Changed is called after every change to the timers or alarms, for a screen to redraw.
	changed func()
}

// New makes the device's tools, with the alarms kept in path. A file that is not there yet is no
// alarms.
func New(host Host, path string, changed func()) (*Device, error) {
	d := &Device{host: host, path: path, now: time.Now, changed: changed}
	b, err := os.ReadFile(path)
	switch {
	case errors.Is(err, os.ErrNotExist):
	case err != nil:
		return nil, err
	default:
		if err := json.Unmarshal(b, &d.alarms); err != nil {
			return nil, fmt.Errorf("device: %s: %w", path, err)
		}
	}
	for _, a := range d.alarms {
		d.nextID = max(d.nextID, a.ID)
	}
	return d, nil
}

// Run checks once a second for anything due, until ctx ends. It goes by the wall clock and not a
// timer of its own, so a device that slept, or whose clock or time zone moved, still rings at the
// time on the clock, and late rather than never.
func (d *Device) Run(ctx context.Context) {
	t := time.NewTicker(time.Second)
	defer t.Stop()
	d.mu.Lock()
	d.checked = d.now().Round(0)
	d.mu.Unlock()
	for {
		select {
		case <-ctx.Done():
			return
		case <-t.C:
			d.Check()
		}
	}
}

// Check rings whatever has come due since the last check.
func (d *Device) Check() {
	now := d.now().Round(0)
	type ring struct{ kind, label string }
	var rings []ring
	d.mu.Lock()
	since := d.checked
	if since.IsZero() || since.After(now) {
		since = now.Add(-time.Second)
	}
	d.checked = now
	kept := d.timers[:0]
	for _, t := range d.timers {
		if !t.End.After(now) {
			rings = append(rings, ring{"timer", t.Label})
			continue
		}
		kept = append(kept, t)
	}
	timersChanged := len(kept) != len(d.timers)
	d.timers = kept
	alarmsChanged := false
	for i := range d.alarms {
		a := &d.alarms[i]
		if !a.On {
			continue
		}
		// An alarm missed by more than ten minutes, while the device was off or asleep, is not rung
		// late: nobody wants a 6:30 alarm at noon.
		from := since
		if now.Sub(from) > 10*time.Minute {
			from = now.Add(-10 * time.Minute)
		}
		if at := a.next(from); !at.After(now) {
			rings = append(rings, ring{"alarm", a.Label})
			if a.Days == DaysOnce {
				a.On = false
				alarmsChanged = true
			}
		}
	}
	if alarmsChanged {
		_ = d.saveLocked()
	}
	d.mu.Unlock()
	for _, r := range rings {
		d.host.Ring(r.kind, r.label)
	}
	if timersChanged || alarmsChanged {
		d.notify()
	}
}

func (d *Device) notify() {
	if d.changed != nil {
		d.changed()
	}
}

// next is the first time strictly after from that the alarm rings, in from's location.
func (a Alarm) next(from time.Time) time.Time {
	day := time.Date(from.Year(), from.Month(), from.Day(), a.Hour, a.Minute, 0, 0, from.Location())
	for i := 0; i < 8; i++ {
		at := day.AddDate(0, 0, i)
		if !at.After(from) {
			continue
		}
		if a.Days == DaysOnce || a.Days&(1<<int(at.Weekday())) != 0 {
			return at
		}
	}
	return day.AddDate(0, 0, 8)
}

// Next is when the alarm rings next, from now.
func (d *Device) Next(a Alarm) time.Time { return a.next(d.now()) }

// Timers are the timers running, soonest first.
func (d *Device) Timers() []Timer {
	d.mu.Lock()
	defer d.mu.Unlock()
	out := slices.Clone(d.timers)
	slices.SortFunc(out, func(a, b Timer) int { return a.End.Compare(b.End) })
	return out
}

// Alarms are the alarms set, in the order they were set.
func (d *Device) Alarms() []Alarm {
	d.mu.Lock()
	defer d.mu.Unlock()
	return slices.Clone(d.alarms)
}

// StartTimer starts a countdown.
func (d *Device) StartTimer(label string, dur time.Duration) Timer {
	d.mu.Lock()
	d.nextID++
	t := Timer{ID: d.nextID, Label: label, End: d.now().Round(0).Add(dur)}
	d.timers = append(d.timers, t)
	d.mu.Unlock()
	d.notify()
	return t
}

// CancelTimer stops a countdown, and says whether there was one of that id.
func (d *Device) CancelTimer(id int) bool {
	d.mu.Lock()
	n := len(d.timers)
	d.timers = slices.DeleteFunc(d.timers, func(t Timer) bool { return t.ID == id })
	ok := len(d.timers) != n
	d.mu.Unlock()
	if ok {
		d.notify()
	}
	return ok
}

// SetAlarm adds an alarm and keeps it.
func (d *Device) SetAlarm(hour, minute int, days Days, label string) (Alarm, error) {
	d.mu.Lock()
	d.nextID++
	a := Alarm{ID: d.nextID, Hour: hour, Minute: minute, Days: days & DaysEvery, Label: label, On: true}
	d.alarms = append(d.alarms, a)
	err := d.saveLocked()
	if err != nil {
		d.alarms = d.alarms[:len(d.alarms)-1]
	}
	d.mu.Unlock()
	if err != nil {
		return Alarm{}, err
	}
	d.notify()
	return a, nil
}

// DeleteAlarm forgets an alarm, and says whether there was one of that id.
func (d *Device) DeleteAlarm(id int) (bool, error) {
	d.mu.Lock()
	before := d.alarms
	d.alarms = slices.DeleteFunc(slices.Clone(d.alarms), func(a Alarm) bool { return a.ID == id })
	if len(d.alarms) == len(before) {
		d.mu.Unlock()
		return false, nil
	}
	err := d.saveLocked()
	if err != nil {
		d.alarms = before
	}
	d.mu.Unlock()
	if err != nil {
		return false, err
	}
	d.notify()
	return true, nil
}

// saveLocked writes the alarms whole, through a temporary file, so a power cut leaves the old list or
// the new one and never half of either.
func (d *Device) saveLocked() error {
	b, err := json.Marshal(d.alarms)
	if err != nil {
		return err
	}
	if err := os.MkdirAll(filepath.Dir(d.path), 0o700); err != nil {
		return err
	}
	tmp := d.path + ".tmp"
	if err := os.WriteFile(tmp, b, 0o600); err != nil {
		return err
	}
	return os.Rename(tmp, d.path)
}

// tool is one command: what Muse is told, and what it runs.
type tool struct {
	name        string
	description string
	required    []muse.Param
	optional    []muse.Param
	run         func(args map[string]any) (string, error)
}

func str(name, desc string) muse.Param {
	return muse.Param{Name: name, Type: muse.String, Description: desc}
}
func num(name, desc string) muse.Param {
	return muse.Param{Name: name, Type: muse.Integer, Description: desc}
}

// Commands are the tools as Muse commands, named so they read as the device's.
func (d *Device) Commands() []muse.Command {
	var out []muse.Command
	for _, t := range d.tools() {
		run := t.run
		out = append(out, muse.Command{
			Name:        "portal." + t.name,
			Description: t.description,
			Required:    t.required,
			Optional:    t.optional,
			Handler: func(_ context.Context, raw json.RawMessage) (any, error) {
				args := map[string]any{}
				if len(raw) > 0 {
					if err := json.Unmarshal(raw, &args); err != nil {
						return nil, fmt.Errorf("the arguments were not a JSON object: %w", err)
					}
				}
				result, err := run(args)
				if err != nil {
					return nil, err
				}
				return map[string]string{"result": result}, nil
			},
		})
	}
	return out
}

// Run runs one tool by its name without the prefix, for a test or a screen.
func (d *Device) RunTool(name string, args map[string]any) (string, error) {
	for _, t := range d.tools() {
		if t.name == name {
			return t.run(args)
		}
	}
	return "", fmt.Errorf("no tool %q", name)
}

func (d *Device) tools() []tool {
	return []tool{
		{name: "start_timer",
			description: "Use when asked to set or start a timer, a countdown, on this device. Starts a countdown timer.",
			required:    []muse.Param{num("seconds", "How long, in seconds.")},
			optional:    []muse.Param{str("label", "What it is for, like pasta; empty if not said.")},
			run: func(a map[string]any) (string, error) {
				s, ok := argNumber(a, "seconds")
				if !ok || s < 1 || s > 24*3600 {
					return "", errors.New("seconds must be between 1 and 86400")
				}
				dur := time.Duration(math.Round(s)) * time.Second
				label := argString(a, "label")
				d.StartTimer(label, dur)
				if label == "" {
					return "started a " + words(dur) + " timer with no label", nil
				}
				return fmt.Sprintf("started a %s timer labeled %q", words(dur), label), nil
			}},

		{name: "list_timers",
			description: "Use when asked what timers are running on this device, or how long is left.",
			run: func(map[string]any) (string, error) {
				list := d.Timers()
				if len(list) == 0 {
					return "no timers", nil
				}
				now := d.now()
				var s []string
				for _, t := range list {
					name := fmt.Sprintf("%q", t.Label)
					if t.Label == "" {
						name = "a timer with no label"
					}
					s = append(s, fmt.Sprintf("%s with %s left", name, words(t.End.Sub(now))))
				}
				return strings.Join(s, "; "), nil
			}},

		{name: "cancel_timers",
			description: "Use when asked to cancel or stop a timer on this device: the one named, or all of them.",
			optional:    []muse.Param{str("label", "The timer's label; empty cancels all.")},
			run: func(a map[string]any) (string, error) {
				label := strings.ToLower(argString(a, "label"))
				list := d.Timers()
				n := 0
				for _, t := range list {
					if label == "" || strings.ToLower(t.Label) == label {
						if d.CancelTimer(t.ID) {
							n++
						}
					}
				}
				// "Cancel the timer" with one running means that one, whatever its label was taken to be.
				if n == 0 && len(list) == 1 && d.CancelTimer(list[0].ID) {
					n = 1
				}
				switch n {
				case 0:
					return "no timer matched, and none was canceled", nil
				case 1:
					return "canceled one timer", nil
				}
				return fmt.Sprintf("canceled %d timers", n), nil
			}},

		{name: "set_alarm",
			description: "Use when asked to set an alarm on this device, or to be woken at a time.",
			required:    []muse.Param{str("time", "The time as the person said it: 6:30, 6:30 pm, 18:30, 7 am. Keep am or pm only if said.")},
			optional: []muse.Param{
				str("days", "once (the next time it comes round), daily, weekdays, weekends, or days like mon,wed,fri. Default once."),
				str("label", "What it is for; empty if not said."),
			},
			run: func(a map[string]any) (string, error) {
				h, m, err := alarmClock(argString(a, "time"))
				if err != nil {
					return "", err
				}
				ds := argString(a, "days")
				if l := strings.ToLower(ds); l == "today" || l == "tomorrow" {
					ds = "once"
				}
				days, err := ParseDays(ds)
				if err != nil {
					return "", err
				}
				al, err := d.SetAlarm(h, m, days, argString(a, "label"))
				if err != nil {
					return "", err
				}
				return fmt.Sprintf("alarm set for %02d:%02d (%s)", al.Hour, al.Minute, DaysLabel(al.Days)), nil
			}},

		{name: "list_alarms",
			description: "Use when asked what alarms are set on this device.",
			run: func(map[string]any) (string, error) {
				var s []string
				for _, al := range d.Alarms() {
					state := "on"
					if !al.On {
						state = "off"
					}
					line := fmt.Sprintf("alarm at %02d:%02d, %s, %s", al.Hour, al.Minute, DaysLabel(al.Days), state)
					if al.Label != "" {
						line += fmt.Sprintf(", labeled %q", al.Label)
					}
					s = append(s, line)
				}
				if len(s) == 0 {
					return "no alarms", nil
				}
				return strings.Join(s, "; "), nil
			}},

		{name: "delete_alarm",
			description: "Use when asked to delete, remove or turn off an alarm on this device, found by its time or label. Use list_alarms first when unsure.",
			optional: []muse.Param{
				str("time", "Its time of day, 24-hour, as HH:MM; empty to go by the label."),
				str("label", "Its label; empty to go by the time."),
			},
			run: func(a map[string]any) (string, error) {
				list := d.Alarms()
				label := strings.ToLower(argString(a, "label"))
				var h, m int
				byTime := argString(a, "time") != ""
				if byTime {
					var err error
					if h, m, err = clock(argString(a, "time")); err != nil {
						return "", err
					}
				}
				var found []Alarm
				for _, al := range list {
					if byTime && (al.Hour != h || al.Minute != m) {
						continue
					}
					if label != "" && strings.ToLower(al.Label) != label {
						continue
					}
					found = append(found, al)
				}
				// "Delete the alarm" with one set means that one.
				if !byTime && label == "" && len(list) == 1 {
					found = list
				}
				switch len(found) {
				case 0:
					return "no alarm matched; nothing was deleted", nil
				case 1:
					if _, err := d.DeleteAlarm(found[0].ID); err != nil {
						return "", err
					}
					return fmt.Sprintf("deleted the %02d:%02d one", found[0].Hour, found[0].Minute), nil
				}
				return fmt.Sprintf("%d alarms matched; ask which one, nothing was deleted", len(found)), nil
			}},

		{name: "stop",
			description: "Use when asked to stop, be quiet, or silence what this device is ringing or saying. It does not delete alarms or cancel timers.",
			run: func(map[string]any) (string, error) {
				rang := d.host.StopRinging()
				d.host.StopSpeaking()
				if rang {
					return "stopped the ringing", nil
				}
				return "nothing was ringing", nil
			}},

		{name: "set_volume",
			description: fmt.Sprintf("Use when asked to set the volume of this device, louder or quieter. Sets the speaker volume, 0 to %d.", VolumeSteps),
			required:    []muse.Param{num("level", fmt.Sprintf("0 to %d.", VolumeSteps))},
			run: func(a map[string]any) (string, error) {
				v, ok := argNumber(a, "level")
				if !ok {
					return "", errors.New("level is a number")
				}
				level := min(max(int(math.Round(v)), 0), VolumeSteps)
				now := d.host.SetVolume(level)
				return fmt.Sprintf("volume is %d of %d", now, VolumeSteps), nil
			}},

		{name: "get_volume",
			description: "Use when asked how loud this device is.",
			run: func(map[string]any) (string, error) {
				return fmt.Sprintf("volume is %d of %d", d.host.Volume(), VolumeSteps), nil
			}},
	}
}

func argString(args map[string]any, k string) string {
	v, _ := args[k].(string)
	return strings.TrimSpace(v)
}

func argNumber(args map[string]any, k string) (float64, bool) {
	switch v := args[k].(type) {
	case float64:
		return v, true
	case string:
		f, err := strconv.ParseFloat(strings.TrimSpace(v), 64)
		return f, err == nil
	}
	return 0, false
}

func words(d time.Duration) string {
	d = d.Round(time.Second)
	h, m, s := int(d.Hours()), int(d.Minutes())%60, int(d.Seconds())%60
	var out []string
	part := func(n int, unit string) {
		if n == 1 {
			out = append(out, "1 "+unit)
		} else if n > 1 {
			out = append(out, fmt.Sprintf("%d %ss", n, unit))
		}
	}
	part(h, "hour")
	part(m, "minute")
	if h == 0 {
		part(s, "second")
	}
	if len(out) == 0 {
		return "0 seconds"
	}
	return strings.Join(out, " ")
}

// alarmClock reads an alarm's time as it was said. Without am or pm, an hour from 4 to 11 is the
// morning and one from 1 to 3 the afternoon; twelve is noon.
func alarmClock(s string) (int, int, error) {
	v := strings.ToLower(strings.Join(strings.Fields(s), ""))
	v = strings.NewReplacer("a.m.", "am", "p.m.", "pm").Replace(v)
	pm, am := strings.HasSuffix(v, "pm"), strings.HasSuffix(v, "am")
	v = strings.TrimSuffix(strings.TrimSuffix(v, "pm"), "am")
	hs, ms, ok := strings.Cut(v, ":")
	if !ok {
		hs, ms = v, "0"
	}
	h, err1 := strconv.Atoi(hs)
	m, err2 := strconv.Atoi(ms)
	if err1 != nil || err2 != nil || h < 0 || h > 23 || m < 0 || m > 59 {
		return 0, 0, fmt.Errorf("%q is not a time of day", s)
	}
	switch {
	case (am || pm) && (h < 1 || h > 12):
		return 0, 0, fmt.Errorf("%q is not a time of day", s)
	case pm && h < 12:
		h += 12
	case am && h == 12:
		h = 0
	case !am && !pm && h >= 1 && h <= 3:
		h += 12
	}
	return h, m, nil
}

// clock reads HH:MM, 24-hour.
func clock(s string) (int, int, error) {
	t, err := time.Parse("15:04", strings.TrimSpace(s))
	if err != nil {
		return 0, 0, fmt.Errorf("%q is not a time as HH:MM", s)
	}
	return t.Hour(), t.Minute(), nil
}

// ParseDays reads once, daily, weekdays, weekends, or a list of days like mon,wed,fri.
func ParseDays(s string) (Days, error) {
	s = strings.ToLower(strings.TrimSpace(s))
	switch s {
	case "", "once":
		return DaysOnce, nil
	case "daily", "every day", "everyday":
		return DaysEvery, nil
	case "weekdays":
		return DaysWeekdays, nil
	case "weekends":
		return DaysWeekends, nil
	}
	var days Days
	for _, part := range strings.FieldsFunc(s, func(r rune) bool { return r == ',' || r == ' ' }) {
		i := slices.IndexFunc(dayNames[:], func(d string) bool { return strings.HasPrefix(part, d) })
		if i < 0 {
			return 0, fmt.Errorf("%q is not a day", part)
		}
		days |= 1 << i
	}
	return days, nil
}

// DaysLabel is how a set of days reads on the screen.
func DaysLabel(days Days) string {
	switch days & DaysEvery {
	case DaysOnce:
		return "once"
	case DaysEvery:
		return "every day"
	case DaysWeekdays:
		return "weekdays"
	case DaysWeekends:
		return "weekends"
	}
	var out []string
	for i, d := range dayNames {
		if days&(1<<i) != 0 {
			out = append(out, strings.ToUpper(d[:1])+d[1:])
		}
	}
	return strings.Join(out, " ")
}
