package main

import (
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"mishka_core/overrides"
)

func TestTransformedMixedPortUsesScriptResult(t *testing.T) {
	workDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), []byte("mixed-port: 7890\nrules: [MATCH,DIRECT]\n"), 0600); err != nil {
		t.Fatal(err)
	}
	scriptPath := filepath.Join(workDir, "port.js")
	if err := os.WriteFile(scriptPath, []byte(`function main(c) { c["mixed-port"] = 7895; return c; }`), 0600); err != nil {
		t.Fatal(err)
	}
	transformPath := filepath.Join(workDir, "transform.json")
	transform, err := json.Marshal(overrides.Transform{Overrides: []overrides.Spec{{Name: "port", Format: overrides.FormatJS, Path: scriptPath}}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(transformPath, transform, 0600); err != nil {
		t.Fatal(err)
	}
	out, err := readTransformedConfig(workDir, transformPath, "")
	if err != nil {
		t.Fatal(err)
	}
	port, err := transformedMixedPort(out)
	if err != nil {
		t.Fatal(err)
	}
	if port != 7895 {
		t.Fatalf("transformed mixed-port = %d, want 7895", port)
	}
}
