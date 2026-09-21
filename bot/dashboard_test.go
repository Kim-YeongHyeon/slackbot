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

func TestArtifactViewer_NoCredentials_Returns401(t *testing.T) {
	// v0.0.73: 아티팩트 뷰어도 로그인 필수 — 무인증 공유 중단. 업스트림에 도달하면 안 된다.
	hit := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/artifacts/view/1/", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401 (뷰어 무인증 접근 차단)", rec.Code)
	}
	if hit {
		t.Fatal("upstream must not be reached without credentials")
	}
}

func TestArtifactReview_ValidCredentials_ReachesUpstreamWithPath(t *testing.T) {
	// 인라인 댓글 리뷰 페이지 (v0.0.75): /artifacts/review/{id} 가 자격증명과 함께
	// 경로 훼손 없이 업스트림(Spring)에 도달해야 한다 (Spring 이 index.html?id= 로 302).
	var gotPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	req := httptest.NewRequest(http.MethodGet, "/artifacts/review/1", nil)
	req.SetBasicAuth("admin", "secret")
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if gotPath != "/artifacts/review/1" {
		t.Fatalf("upstream path = %q, want /artifacts/review/1 (프록시가 경로를 변형하면 안 됨)", gotPath)
	}
}

func TestArtifactReview_NoCredentials_Returns401(t *testing.T) {
	// 리뷰 페이지도 로그인 필수 — 무인증이면 업스트림에 도달하면 안 된다.
	hit := false
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		hit = true
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/artifacts/review/1", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if hit {
		t.Fatal("upstream must not be reached without credentials")
	}
}

func TestArtifactViewer_ValidCredentials_AssetSubPathPreserved(t *testing.T) {
	// 자산 서빙 (v0.0.72): /artifacts/view/{id}/page_files/... 하위 경로가
	// 훼손 없이 그대로 업스트림에 전달되어야 한다 (한글 파일명 포함).
	var gotPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.EscapedPath()
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	d := newTestProxy(t, upstream.URL)
	const path = "/artifacts/view/1/page_files/%EC%9D%B4%EB%AF%B8%EC%A7%80.png"
	req := httptest.NewRequest(http.MethodGet, path, nil)
	req.SetBasicAuth("admin", "secret")
	rec := httptest.NewRecorder()
	d.ServeHTTP(rec, req)

	if rec.Code != http.StatusOK {
		t.Fatalf("status = %d, want 200", rec.Code)
	}
	if gotPath != path {
		t.Fatalf("upstream path = %q, want %q (프록시가 하위 경로를 변형하면 안 됨)", gotPath, path)
	}
}
