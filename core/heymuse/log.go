package heymuse

import (
	"context"
	"fmt"
	"log/slog"
	"strings"
)

// hostLog sends the core's log to the app, for logcat. Attributes follow the message as key=value.
type hostLog struct {
	host  Host
	attrs []slog.Attr
	group string
}

func (h *hostLog) Enabled(_ context.Context, l slog.Level) bool { return l >= slog.LevelInfo }

func (h *hostLog) Handle(_ context.Context, r slog.Record) error {
	var b strings.Builder
	b.WriteString(r.Message)
	write := func(a slog.Attr) bool {
		key := a.Key
		if h.group != "" {
			key = h.group + "." + key
		}
		fmt.Fprintf(&b, " %s=%v", key, a.Value.Any())
		return true
	}
	for _, a := range h.attrs {
		write(a)
	}
	r.Attrs(write)
	level := "info"
	switch {
	case r.Level >= slog.LevelError:
		level = "error"
	case r.Level >= slog.LevelWarn:
		level = "warn"
	case r.Level < slog.LevelInfo:
		level = "debug"
	}
	h.host.Log(level, b.String())
	return nil
}

func (h *hostLog) WithAttrs(attrs []slog.Attr) slog.Handler {
	return &hostLog{host: h.host, attrs: append(append([]slog.Attr{}, h.attrs...), attrs...), group: h.group}
}

func (h *hostLog) WithGroup(name string) slog.Handler {
	g := name
	if h.group != "" {
		g = h.group + "." + name
	}
	return &hostLog{host: h.host, attrs: h.attrs, group: g}
}
