package main

import (
	"io"
	"log/slog"
	"net/http"
	"net/http/httptest"
	"testing"
)

func newTestProxy(t *testing.T, upstream string) *DashboardProxy {
	t.Helper()
	d, err := NewDashboardProxy(upstream, "admin", "secret", slog.Default())
	if err != nil {
		t.Fatalf("NewDashboardProxy: %v", err)
	}
	return d
}

func TestDashboardProxy_NoCredentials_Returns401(t *testing.T) {
	hit := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/dashboard/", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if rec.Header().Get("WWW-Authenticate") == "" {
		t.Fatal("missing WWW-Authenticate header (browser prompt depends on it)")
	}
	if hit {
		t.Fatal("upstream must not be reached without credentials")
	}
}

func TestDashboardProxy_WrongPassword_Returns401(t *testing.T) {
	hit := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	req := httptest.NewRequest(http.MethodGet, "/dashboard/", nil)
	req.SetBasicAuth("admin", "wrong")
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, req)

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if hit {
		t.Fatal("upstream must not be reached with wrong password")
	}
}

func TestDashboardProxy_ValidCredentials_ProxiesPathAndQuery(t *testing.T) {
	var gotPath, gotQuery string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	req := httptest.NewRequest(http.MethodGet, "/api/dashboard/trends?weeks=12", nil)
	req.SetBasicAuth("admin", "secret")
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if gotPath != "/api/dashboard/trends" || gotQuery != "weeks=12" {
		t.Fatalf("upstream got %q?%q, want /api/dashboard/trends?weeks=12", gotPath, gotQuery)
	}
	body, _ := io.ReadAll(rec.Body)
	if string(body) != `{"ok":true}` {
		t.Fatalf("body = %s, want upstream body", body)
	}
}

func TestDashboardProxy_ProxiesPostMethod(t *testing.T) {
	var gotMethod string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotMethod = r.Method
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	req := httptest.NewRequest(http.MethodPost, "/api/dashboard/actions/sync", nil)
	req.SetBasicAuth("admin", "secret")
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, req)

	if gotMethod != http.MethodPost {
		t.Fatalf("upstream method = %q, want POST (수동 동기화 버튼)", gotMethod)
	}
}
