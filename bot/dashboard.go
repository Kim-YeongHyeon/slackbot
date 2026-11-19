package main

import (
	"net/http"
	"net/http/httputil"
	"net/url"
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
	d.proxy.ServeHTTP(w, r)
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
