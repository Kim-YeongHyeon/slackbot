package main

import (
	"bytes"
	"io"
	"log/slog"
	"net/http"
	"time"
)

// Jira webhook payloads (changelog + issue fields) are larger than Slack events.
const jiraMaxBodyBytes = 2 << 20 // 2 MiB

// JiraWebhookHandler proxies Jira webhooks to the Spring Boot server.
//
// The ngrok tunnel only exposes this bot (:3000), so Jira cannot reach Spring
// (:8080) directly — this handler bridges the gap. The ?token=... query string
// is preserved untouched because Spring's JiraWebhookController authenticates
// with it. Spring's HTTP status is mirrored back so a misconfigured token
// surfaces as 403 in Jira's webhook delivery log instead of being masked.
type JiraWebhookHandler struct {
	springURL string
	client    *http.Client
	logger    *slog.Logger
}

func NewJiraWebhookHandler(springURL string, client *http.Client, logger *slog.Logger) *JiraWebhookHandler {
	if client == nil {
		client = &http.Client{Timeout: 10 * time.Second}
	}
	return &JiraWebhookHandler{springURL: springURL, client: client, logger: logger}
}

func (h *JiraWebhookHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, jiraMaxBodyBytes))
	if err != nil {
		h.logger.Warn("jira webhook: failed to read body", "err", err)
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	target := h.springURL
	if r.URL.RawQuery != "" {
		target += "?" + r.URL.RawQuery
	}

	req, err := http.NewRequestWithContext(r.Context(), http.MethodPost, target, bytes.NewReader(body))
	if err != nil {
		h.logger.Error("jira webhook: build upstream request failed", "err", err)
		http.Error(w, "internal error", http.StatusInternalServerError)
		return
	}
	req.Header.Set("Content-Type", "application/json")

	resp, err := h.client.Do(req)
	if err != nil {
		h.logger.Error("jira webhook: forward to spring failed", "err", err)
		http.Error(w, "upstream unavailable", http.StatusBadGateway)
		return
	}
	defer resp.Body.Close()
	_, _ = io.Copy(io.Discard, io.LimitReader(resp.Body, 4096))

	h.logger.Info("jira webhook forwarded", "status", resp.StatusCode, "bytes", len(body))
	w.WriteHeader(resp.StatusCode)
}
