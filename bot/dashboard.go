package main

import (
	"net"
	"net/http"
	"net/http/httputil"
	"net/url"
	"strings"
)

// DashboardProxy exposes the Spring web dashboard through the ngrok tunnel.
//
// The tunnel only exposes this bot (:3000); the dashboard lives on Spring
// (:8080). Since v0.0.79 authentication lives entirely in Spring (HTTP Basic
// against the admin account in .env + DB-backed user accounts) — this proxy
// cannot see the DB, so checking credentials here would lock out every
// non-admin user. The Authorization header is passed through unchanged and
// Spring's 401 + WWW-Authenticate is relayed back to the browser.
//
// Routes are registered per allowed prefix by RegisterDashboardRoutes — the
// handler itself does not re-check paths, so never mount it on "/".
type DashboardProxy struct {
	proxy *httputil.ReverseProxy
}

func NewDashboardProxy(springBaseURL string) (*DashboardProxy, error) {
	target, err := url.Parse(springBaseURL)
	if err != nil {
		return nil, err
	}
	return &DashboardProxy{proxy: httputil.NewSingleHostReverseProxy(target)}, nil
}

func (d *DashboardProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if c := ngrokWarningCookie(r.Host); c != nil {
		http.SetCookie(w, c)
	}
	d.proxy.ServeHTTP(w, r)
}

// ngrokFreeDomainSuffixes are the base domains of ngrok's free plan, which puts
// a "You are about to visit…" warning page (ERR_NGROK_6024) in front of browser
// requests that lack its acknowledgement cookie.
var ngrokFreeDomainSuffixes = []string{".ngrok-free.dev", ".ngrok-free.app"}

// ngrokWarningCookie re-issues ngrok's warning-acknowledgement cookie from this
// server (v0.0.107).
//
// ngrok's own page sets `abuse_interstitial=<host>` from JavaScript with
// SameSite=None and a 7-day expiry. Safari does not attach that cookie to
// fetch() requests or to requests inside an iframe, so the edge swapped the
// embedded Grafana's scripts and API calls for the warning page and the tab
// failed with "Grafana has failed to load its application files". The edge
// only checks the cookie's value (the host name), so a server-set,
// first-party cookie — the kind Safari sends with every same-site request —
// with the same name replaces it. A browser's very first visit still sees the
// warning once, because that request never reaches this server.
//
// Returns nil unless the request came in on an ngrok free domain.
func ngrokWarningCookie(hostport string) *http.Cookie {
	host := strings.ToLower(hostport)
	if h, _, err := net.SplitHostPort(host); err == nil {
		host = h
	}
	if !validHostName(host) {
		return nil
	}
	for _, suffix := range ngrokFreeDomainSuffixes {
		if strings.HasSuffix(host, suffix) && len(host) > len(suffix) {
			return &http.Cookie{
				Name:     "abuse_interstitial",
				Value:    host,
				Path:     "/",
				MaxAge:   365 * 24 * 60 * 60,
				Secure:   true,
				HttpOnly: true,
				SameSite: http.SameSiteLaxMode,
			}
		}
	}
	return nil
}

func validHostName(host string) bool {
	if host == "" || len(host) > 253 {
		return false
	}
	for _, c := range host {
		if !(c >= 'a' && c <= 'z' || c >= '0' && c <= '9' || c == '-' || c == '.') {
			return false
		}
	}
	return true
}

// DashboardProxyPaths is the whitelist of Spring paths reachable through the
// tunnel. Every other Spring API stays 404 on the tunnel.
var DashboardProxyPaths = []string{
	"/dashboard", "/dashboard/", // 정적 페이지
	"/api/dashboard/",                           // 통계/응답시간/동기화 API
	"/api/user-mappings", "/api/user-mappings/", // 사용자 관리 탭
	"/api/feature-requests", "/api/feature-requests/", // 기능요청 게시판
	"/api/github-mappings", "/api/github-mappings/", // GitHub↔Jira 매핑
	"/api/artifacts", "/api/artifacts/", // 아티팩트 목록/업로드/삭제
	"/api/artifact-folders", "/api/artifact-folders/", // 아티팩트 주제 폴더 (v0.0.86)
	"/api/admin/",        // 회원 관리 (관리자 전용 — Spring 이 ROLE_ADMIN 검사, v0.0.79)
	"/artifacts/view/",   // 아티팩트 뷰어 — v0.0.73 부터 로그인 필수 (무인증 공유 중단)
	"/artifacts/review/", // 인라인 댓글 리뷰 페이지 (v0.0.75)
	"/actuator/health",   // 봇 상태 탭의 서버 health 카드
	"/grafana/",          // 임베드 탭의 Grafana (v0.0.104) — 로그인은 Spring 이 검사
}

// RegisterDashboardRoutes mounts the proxy on every whitelisted path.
func RegisterDashboardRoutes(mux *http.ServeMux, dash http.Handler) {
	for _, p := range DashboardProxyPaths {
		mux.Handle(p, dash)
	}
}

// RootRedirect sends the bare tunnel URL (https://<domain>/) to the dashboard.
// Without it people who open the domain itself get Go's plain "404 page not
// found" and assume access is blocked (v0.0.84). Mounted on the exact-root
// pattern "/{$}" so every other unmatched path still 404s.
func RootRedirect(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/dashboard/", http.StatusFound)
}
