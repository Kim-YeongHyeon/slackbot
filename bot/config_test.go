package main

import (
	"os"
	"testing"
)

func TestLoadConfig_InteractionURL_Default(t *testing.T) {
	os.Setenv("SLACK_SIGNING_SECRET", "test-secret")
	defer os.Unsetenv("SLACK_SIGNING_SECRET")
	os.Unsetenv("SPRING_INTERACTION_URL")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	expected := "http://localhost:8080/api/slack/interaction"
	if cfg.SpringInteractionURL != expected {
		t.Errorf("expected %q, got %q", expected, cfg.SpringInteractionURL)
	}
}

func TestLoadConfig_InteractionURL_Custom(t *testing.T) {
	os.Setenv("SLACK_SIGNING_SECRET", "test-secret")
	defer os.Unsetenv("SLACK_SIGNING_SECRET")
	os.Setenv("SPRING_INTERACTION_URL", "http://custom:9090/interact")
	defer os.Unsetenv("SPRING_INTERACTION_URL")

	cfg, err := LoadConfig()
	if err != nil {
		t.Fatalf("unexpected error: %v", err)
	}
	if cfg.SpringInteractionURL != "http://custom:9090/interact" {
		t.Errorf("expected custom URL, got %q", cfg.SpringInteractionURL)
	}
}
