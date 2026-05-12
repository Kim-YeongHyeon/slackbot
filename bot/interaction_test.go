package main

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestInteractionHandler_ForwardsPayload(t *testing.T) {
	// Upstream mock that accepts the forwarded request
	var receivedBody string
	var receivedContentType string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		body, _ := io.ReadAll(r.Body)
		receivedBody = string(body)
		receivedContentType = r.Header.Get("Content-Type")
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fwd := NewForwarder(upstream.URL, upstream.Client(), logger)
	handler := NewInteractionHandler(fwd, logger)

	// Simulate a Slack interaction payload (form-encoded)
	formBody := "payload=%7B%22type%22%3A%22block_actions%22%7D"
	req := httptest.NewRequest(http.MethodPost, "/slack/interactions", strings.NewReader(formBody))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")
	req.Header.Set("X-Slack-Signature", "v0=test")
	req.Header.Set("X-Slack-Request-Timestamp", "1234567890")

	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusOK {
		t.Errorf("expected 200, got %d", rr.Code)
	}
	if receivedBody != formBody {
		t.Errorf("upstream received wrong body: %q", receivedBody)
	}
	if receivedContentType != "application/x-www-form-urlencoded" {
		t.Errorf("upstream received wrong content-type: %q", receivedContentType)
	}
}

func TestInteractionHandler_RejectsGet(t *testing.T) {
	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fwd := NewForwarder("http://localhost:9999", nil, logger)
	handler := NewInteractionHandler(fwd, logger)

	req := httptest.NewRequest(http.MethodGet, "/slack/interactions", nil)
	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusMethodNotAllowed {
		t.Errorf("expected 405, got %d", rr.Code)
	}
}

func TestInteractionHandler_UpstreamError(t *testing.T) {
	// Upstream that returns 500
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusInternalServerError)
	}))
	defer upstream.Close()

	logger := slog.New(slog.NewTextHandler(io.Discard, nil))
	fwd := NewForwarder(upstream.URL, upstream.Client(), logger)
	handler := NewInteractionHandler(fwd, logger)

	req := httptest.NewRequest(http.MethodPost, "/slack/interactions", strings.NewReader("payload=test"))
	req.Header.Set("Content-Type", "application/x-www-form-urlencoded")

	rr := httptest.NewRecorder()
	handler.ServeHTTP(rr, req)

	if rr.Code != http.StatusBadGateway {
		t.Errorf("expected 502, got %d", rr.Code)
	}
}
