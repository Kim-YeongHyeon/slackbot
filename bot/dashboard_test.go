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

func TestRootRedirect_OnlyExactRoot(t *testing.T) {
	// 도메인만 열면 대시보드로 이동, 그 외 미등록 경로는 여전히 404 (루트 패턴이 전체를 삼키면 안 됨).
	mux := http.NewServeMux()
	mux.HandleFunc("/{$}", RootRedirect)

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/", nil))
	if rec.Code != http.StatusFound || rec.Header().Get("Location") != "/dashboard/" {
		t.Fatalf("GET / = %d Location=%q, want 302 /dashboard/", rec.Code, rec.Header().Get("Location"))
	}

	rec = httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/random-path", nil))
	if rec.Code != http.StatusNotFound {
		t.Fatalf("GET /random-path = %d, want 404", rec.Code)
	}
}

func TestRegisterDashboardRoutes_GrafanaReachableOthersNot(t *testing.T) {
	// v0.0.104: 임베드 탭의 /grafana/ 는 터널로 열리고(로그인은 Spring), 화이트리스트 밖 Spring API 는 계속 404.
	var gotPath string
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		gotPath = r.URL.RequestURI()
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()
	mux := http.NewServeMux()
	RegisterDashboardRoutes(mux, newTestProxy(t, upstream.URL))

	rec := httptest.NewRecorder()
	mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, "/grafana/d/abc?kiosk", nil))
	if rec.Code != http.StatusOK || gotPath != "/grafana/d/abc?kiosk" {
		t.Fatalf("GET /grafana/d/abc = %d upstream=%q, want 200 /grafana/d/abc?kiosk", rec.Code, gotPath)
	}

	for _, p := range []string{"/api/slack/event", "/api/jira/webhook", "/grafana-evil/x", "/api/ci-alerts/replay"} {
		gotPath = ""
		rec = httptest.NewRecorder()
		mux.ServeHTTP(rec, httptest.NewRequest(http.MethodGet, p, nil))
		if rec.Code != http.StatusNotFound || gotPath != "" {
			t.Fatalf("GET %s = %d upstream=%q, want 404 without reaching Spring", p, rec.Code, gotPath)
		}
	}
}

// v0.0.107: Safari 가 ngrok 이 JS 로 심은 경고 통과 쿠키를 fetch·iframe 요청에 붙이지 않아 임베드된 Grafana 가
// 경고 페이지로 바뀌던 문제 — 서버가 같은 쿠키를 일반 1st-party 쿠키(Lax, Secure, 1년)로 다시 심는다.

func findCookie(rec *httptest.ResponseRecorder, name string) *http.Cookie {
	for _, c := range rec.Result().Cookies() {
		if c.Name == name {
			return c
		}
	}
	return nil
}

func TestDashboardProxy_ReissuesNgrokWarningCookieOnFreeDomain(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.Header().Add("Set-Cookie", "upstream=1; Path=/")
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	req := httptest.NewRequest(http.MethodGet, "/grafana/public/build/app.js", nil)
	req.Host = "Some-Name.ngrok-free.dev"
	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, req)

	c := findCookie(rec, "abuse_interstitial")
	if c == nil {
		t.Fatalf("no abuse_interstitial cookie; Set-Cookie=%v", rec.Header().Values("Set-Cookie"))
	}
	// ngrok 엣지는 값이 호스트 이름인지만 본다 (소문자)
	if c.Value != "some-name.ngrok-free.dev" || c.Path != "/" || !c.Secure || !c.HttpOnly ||
		c.SameSite != http.SameSiteLaxMode || c.MaxAge < 30*24*60*60 {
		t.Fatalf("cookie = %+v, want value=host Path=/ Secure HttpOnly SameSite=Lax long-lived", c)
	}
	if findCookie(rec, "upstream") == nil {
		t.Fatal("upstream Set-Cookie was dropped")
	}
}

func TestNgrokWarningCookie_OnlyForNgrokFreeHosts(t *testing.T) {
	ok := map[string]string{
		"abc.ngrok-free.dev":     "abc.ngrok-free.dev",
		"abc.ngrok-free.app:443": "abc.ngrok-free.app",
	}
	for in, want := range ok {
		if c := ngrokWarningCookie(in); c == nil || c.Value != want {
			t.Errorf("ngrokWarningCookie(%q) = %+v, want value %q", in, c, want)
		}
	}
	for _, in := range []string{
		"", "localhost:3000", "127.0.0.1:3000", "example.com",
		"ngrok-free.dev",                               // 서브도메인 없는 기반 도메인
		"abc.ngrok-free.dev.attacker.example",          // 접미사 흉내
		"abc.ngrok.app",                                // 유료 도메인 — 경고 페이지 없음
		"a b.ngrok-free.dev", "abc.ngrok-free.dev;x=1", // 헤더에 못 넣는 문자
	} {
		if c := ngrokWarningCookie(in); c != nil {
			t.Errorf("ngrokWarningCookie(%q) = %+v, want nil", in, c)
		}
	}
}

func TestDashboardProxy_NoNgrokCookieForInternalHost(t *testing.T) {
	upstream := httptest.NewServer(http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
	}))
	defer upstream.Close()

	req := httptest.NewRequest(http.MethodGet, "/dashboard/", nil)
	req.Host = "localhost:3000"
	rec := httptest.NewRecorder()
	newTestProxy(t, upstream.URL).ServeHTTP(rec, req)

	if c := findCookie(rec, "abuse_interstitial"); c != nil {
		t.Fatalf("unexpected cookie for internal host: %+v", c)
	}
}
