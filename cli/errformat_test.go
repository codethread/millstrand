package cli_test

import (
	"os"
	"testing"
)

// The integration binaries inherit this process's environment, so a
// MILLSTRAND_ERROR_FORMAT from the developer's shell would reshape the stderr these
// tests assert on.
func TestMain(m *testing.M) {
	_ = os.Unsetenv("MILLSTRAND_ERROR_FORMAT")
	os.Exit(m.Run())
}
