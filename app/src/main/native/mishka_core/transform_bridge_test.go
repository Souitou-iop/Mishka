package main

import (
	"bytes"
	"encoding/json"
	"os"
	"path/filepath"
	"testing"

	"github.com/metacubex/mihomo/component/age"
	"github.com/metacubex/mihomo/config"

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

func TestReadConfigWithoutTransform(t *testing.T) {
	workDir := t.TempDir()
	plain := []byte("mixed-port: 7890\nrules: [\"MATCH,DIRECT\"]\n")
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), plain, 0600); err != nil {
		t.Fatal(err)
	}
	out, err := readTransformedConfig(workDir, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(out, plain) {
		t.Fatal("config changed without a transform")
	}
}

func TestReadConfigWithoutTransformRejectsMissingFile(t *testing.T) {
	if _, err := readTransformedConfig(t.TempDir(), "", ""); err == nil {
		t.Fatal("missing config must fail even without a transform")
	}
}

func TestReadEncryptedConfigWithoutTransform(t *testing.T) {
	key, publicKey, err := age.GenX25519KeyPair()
	if err != nil {
		t.Fatal(err)
	}
	plain := []byte("mixed-port: 7890\nrules: [\"MATCH,DIRECT\"]\n")
	ciphertext, err := age.EncryptBytes(plain, publicKey)
	if err != nil {
		t.Fatal(err)
	}
	workDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), ciphertext, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readTransformedConfig(workDir, "", ""); err == nil {
		t.Fatal("encrypted config without its key must fail")
	}
	out, err := readTransformedConfig(workDir, "", key)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(out, plain) {
		t.Fatal("decrypted config differs from original")
	}
	stored, err := os.ReadFile(filepath.Join(workDir, "config.yaml"))
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(stored, ciphertext) {
		t.Fatal("validation must leave the encrypted file unchanged")
	}
}

func TestMalformedConfigWithoutTransformIsNotValid(t *testing.T) {
	workDir := t.TempDir()
	if err := os.WriteFile(filepath.Join(workDir, "config.yaml"), []byte("dns: ["), 0600); err != nil {
		t.Fatal(err)
	}
	out, err := readTransformedConfig(workDir, "", "")
	if err != nil {
		t.Fatal(err)
	}
	if _, err := config.UnmarshalRawConfig(out); err == nil {
		t.Fatal("malformed YAML must fail the native parser without a transform")
	}
}

func TestUpdatedConfigBreakingSelectedTransformFails(t *testing.T) {
	workDir := t.TempDir()
	configPath := filepath.Join(workDir, "config.yaml")
	scriptPath := filepath.Join(workDir, "dns.js")
	if err := os.WriteFile(scriptPath, []byte(`function main(c) { c.dns.nameserver.push("8.8.8.8"); return c; }`), 0600); err != nil {
		t.Fatal(err)
	}
	transformPath := filepath.Join(workDir, "transform.json")
	manifest, err := json.Marshal(overrides.Transform{Overrides: []overrides.Spec{{Name: "dns", Format: overrides.FormatJS, Path: scriptPath}}})
	if err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(transformPath, manifest, 0600); err != nil {
		t.Fatal(err)
	}
	if err := os.WriteFile(configPath, []byte("dns: {nameserver: [1.1.1.1]}\nrules: [\"MATCH,DIRECT\"]\n"), 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readTransformedConfig(workDir, transformPath, ""); err != nil {
		t.Fatalf("original configuration should satisfy script: %v", err)
	}
	updated := []byte("mixed-port: 7890\nrules: [\"MATCH,DIRECT\"]\n")
	if _, err := config.UnmarshalRawConfig(updated); err != nil {
		t.Fatalf("updated YAML itself must remain valid: %v", err)
	}
	if err := os.WriteFile(configPath, updated, 0600); err != nil {
		t.Fatal(err)
	}
	if _, err := readTransformedConfig(workDir, transformPath, ""); err == nil {
		t.Fatal("updated configuration removed a required script input but validation succeeded")
	}
	stored, err := os.ReadFile(configPath)
	if err != nil {
		t.Fatal(err)
	}
	if !bytes.Equal(stored, updated) {
		t.Fatal("failed transform must not modify the source config")
	}
}
