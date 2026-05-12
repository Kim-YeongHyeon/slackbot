package main

import (
	"fmt"
	"os"
	"strings"
	"time"
)

type Config struct {
	Port               string
	SpringURL          string
	SpringInteractionURL string
	SigningSecret      string
	BotToken           string
	ForwardTimeout     time.Duration
}

func LoadConfig() (Config, error) {
	cfg := Config{
		Port:               envOr("PORT", "3000"),
		SpringURL:          envOr("SPRING_EVENT_URL", "http://localhost:8080/api/slack/event"),
		SpringInteractionURL: envOr("SPRING_INTERACTION_URL", "http://localhost:8080/api/slack/interaction"),
		SigningSecret:      os.Getenv("SLACK_SIGNING_SECRET"),
		BotToken:           os.Getenv("SLACK_BOT_TOKEN"),
		ForwardTimeout:     5 * time.Second,
	}

	// SLACK_SIGNING_SECRET is intentionally validated here even though this proxy
	// does NOT perform HMAC verification itself — that responsibility is owned by
	// SlackSignatureFilter on the Spring side. The startup check exists purely so
	// that env-parity mistakes (e.g. a missing variable in .env) surface
	// immediately rather than after the first Slack event hits Spring and 403s.
	// BotToken is allowed to be empty for now — reserved for future outbound
	// Slack API calls (reactions, replies, etc.) from the bot.
	if strings.TrimSpace(cfg.SigningSecret) == "" {
		return Config{}, fmt.Errorf("SLACK_SIGNING_SECRET is required (startup env-parity check; HMAC is verified by Spring)")
	}
	if !strings.HasPrefix(cfg.SpringURL, "http://") && !strings.HasPrefix(cfg.SpringURL, "https://") {
		return Config{}, fmt.Errorf("SPRING_EVENT_URL must be an http(s) URL, got %q", cfg.SpringURL)
	}
	return cfg, nil
}

func envOr(key, fallback string) string {
	if v := os.Getenv(key); v != "" {
		return v
	}
	return fallback
}
