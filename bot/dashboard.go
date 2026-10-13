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
// Routes must be registered per allowed prefix in main.go — the handler
// itself does not re-check paths, so never mount it on "/".
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

// RootRedirect sends the bare tunnel URL (https://<domain>/) to the dashboard.
// Without it people who open the domain itself get Go's plain "404 page not
// found" and assume access is blocked (v0.0.84). Mounted on the exact-root
// pattern "/{$}" so every other unmatched path still 404s.
func RootRedirect(w http.ResponseWriter, r *http.Request) {
	http.Redirect(w, r, "/dashboard/", http.StatusFound)
}
