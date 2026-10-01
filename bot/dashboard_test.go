package main

import (
	"io"
	"net/http"
	"net/http/httptest"
	"testing"
)

// v0.0.79: 인증은 Spring 전담 — 프록시는 순수 전달. 여기서는 "그대로 전달/중계" 만 검증한다.

func newTestProxy(t *testing.T, upstream string) *DashboardProxy {
	t.Helper()
	d, err := NewDashboardProxy(upstream)
	if err != nil {
		t.Fatalf("NewDashboardProxy: %v", err)
	}
	return d
}

func TestDashboardProxy_PassesAuthorizationHeaderUnchanged(t *testing.T) {
	// DB 사용자(관리자 아닌 계정)의 자격증명도 Spring 까지 그대로 가야 한다 — 프록시가 막으면 일반 사용자 전원 로그인 불가.
	var gotUser, gotPass string
	var gotOK bool
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotUser, gotPass, gotOK = r.BasicAuth()
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	req := httptest.NewRequest(http.MethodGet, "/dashboard/", nil)
	req.SetBasicAuth("kim", "pw1234")
	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, req)

	if !gotOK || gotUser != "kim" || gotPass != "pw1234" {
		t.Fatalf("upstream basic auth = (%q,%q,%v), want (kim,pw1234,true)", gotUser, gotPass, gotOK)
	}
}

func TestDashboardProxy_RelaysUpstream401WithChallenge(t *testing.T) {
	// 무인증이면 Spring 의 401 + WWW-Authenticate 가 브라우저까지 와야 로그인 팝업이 뜬다.
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Set("WWW-Authenticate", `Basic realm="sol dashboard"`)
		w.WriteHeader(http.StatusUnauthorized)
	}))
	defer upstream.Close()

	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/dashboard/", nil))

	if rec.Code != http.StatusUnauthorized {
		t.Fatalf("status = %d, want 401", rec.Code)
	}
	if rec.Header().Get("WWW-Authenticate") == "" {
		t.Fatal("missing WWW-Authenticate (browser login prompt depends on it)")
	}
}

func TestDashboardProxy_ProxiesPathAndQuery(t *testing.T) {
	var gotPath, gotQuery string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.Path
		gotQuery = r.URL.RawQuery
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte(`{"ok":true}`))
	}))
	defer upstream.Close()

	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/api/dashboard/trends?weeks=12", nil))

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

	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, httptest.NewRequest(http.MethodPost, "/api/admin/users", nil))

	if gotMethod != http.MethodPost {
		t.Fatalf("upstream method = %q, want POST", gotMethod)
	}
}

func TestDashboardProxy_SubPathsPreserved(t *testing.T) {
	// 아티팩트 자산(한글 파일명)·리뷰 페이지 하위 경로가 훼손 없이 전달되어야 한다.
	for _, path := range []string{
		"/artifacts/view/1/page_files/%EC%9D%B4%EB%AF%B8%EC%A7%80.png",
		"/artifacts/review/1",
		"/api/admin/users/3",
	} {
		var gotPath string
		upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
			gotPath = r.URL.EscapedPath()
			w.WriteHeader(http.StatusOK)
		}))
		rec := httptest.NewRecorder()
		newTestProxy(t, upstream.URL).ServeHTTP(rec, httptest.NewRequest(http.MethodGet, path, nil))
		upstream.Close()
		if gotPath != path {
			t.Fatalf("upstream path = %q, want %q", gotPath, path)
		}
	}
}
