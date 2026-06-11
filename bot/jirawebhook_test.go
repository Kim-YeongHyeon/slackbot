package main

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"strings"
	"testing"
)

func TestJiraWebhookHandler_ForwardsBodyAndQuery(t *testing.T) {
	var gotQuery, gotBody string
	spring := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotQuery = r.URL.RawQuery
		b, _ := io.ReadAll(r.Body)
		gotBody = string(b)
		w.WriteHeader(http.StatusOK)
	}))
	defer spring.Close()

	h := NewJiraWebhookHandler(spring.URL, spring.Client(), slog.Default())
	req := httptest.NewRequest(http.MethodPost, "/api/jira/webhook?token=sec-1", strings.NewReader(`{"webhookEvent":"jira:issue_updated"}`))
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("expected 200, got %d", rec.Code)
	}
	if gotQuery != "token=sec-1" {
		t.Fatalf("query not preserved, got %q", gotQuery)
	}
	if !strings.Contains(gotBody, "jira:issue_updated") {
		t.Fatalf("body not forwarded, got %q", gotBody)
	}
}

func TestJiraWebhookHandler_MirrorsUpstreamStatus(t *testing.T) {
	spring := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusForbidden) // token mismatch on Spring side
	}))
	defer spring.Close()

	h := NewJiraWebhookHandler(spring.URL, spring.Client(), slog.Default())
	req := httptest.NewRequest(http.MethodPost, "/api/jira/webhook?token=wrong", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusForbidden {
		t.Fatalf("expected mirrored 403, got %d", rec.Code)
	}
}

func TestJiraWebhookHandler_RejectsNonPost(t *testing.T) {
	h := NewJiraWebhookHandler("http://unused", nil, slog.Default())
	req := httptest.NewRequest(http.MethodGet, "/api/jira/webhook", nil)
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusMethodNotAllowed {
		t.Fatalf("expected 405, got %d", rec.Code)
	}
}

func TestJiraWebhookHandler_UpstreamDown_Returns502(t *testing.T) {
	// 닫힌 서버 주소로 포워딩 → 502.
	spring := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {}))
	url := spring.URL
	spring.Close()

	h := NewJiraWebhookHandler(url, nil, slog.Default())
	req := httptest.NewRequest(http.MethodPost, "/api/jira/webhook?token=x", strings.NewReader(`{}`))
	rec := httptest.NewRecorder()

	h.ServeHTTP(rec, req)

	if rec.Code != http.StatusBadGateway {
		t.Fatalf("expected 502, got %d", rec.Code)
	}
}
