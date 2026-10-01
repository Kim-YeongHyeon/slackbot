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
