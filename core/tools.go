//go:build tools

// The gomobile bind step needs golang.org/x/mobile/bind in this module.
package core

import _ "golang.org/x/mobile/bind"
