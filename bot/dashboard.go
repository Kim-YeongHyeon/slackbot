package main

import (
	"crypto/subtle"
	"log/slog"
	"net/http"
	"net/http/httputil"
	"net/url"
)

// DashboardProxy exposes the Spring web dashboard through the ngrok tunnel
// behind HTTP Basic Auth.
//
// The tunnel only exposes this bot (:3000); the dashboard lives on Spring
// (:8080) which is otherwise reachable only via SSH port-forwarding. This
// proxy bridges the gap for browsers. Auth lives HERE (not Spring) because
// the internal network path (http://host:8080/dashboard/) stays open for
// LAN use — only the public tunnel needs a lock.
//
// Routes must be registered per allowed prefix in main.go — the handler
// itself does not re-check paths, so never mount it on "/".
type DashboardProxy struct {
	proxy  *httputil.ReverseProxy
	user   string
	pass   string
	logger *slog.Logger
}

func NewDashboardProxy(springBaseURL, user, pass string, logger *slog.Logger) (*DashboardProxy, error) {
	target, err := url.Parse(springBaseURL)
	if err != nil {
		return nil, err
	}
	return &DashboardProxy{
		proxy:  httputil.NewSingleHostReverseProxy(target),
		user:   user,
		pass:   pass,
		logger: logger,
	}, nil
}

func (d *DashboardProxy) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	u, p, ok := r.BasicAuth()
	// STUDY(go): subtle.ConstantTimeCompare — 일반 == 비교는 일치 길이만큼 빨리 끝나
	// 타이밍으로 비번을 한 글자씩 추측할 수 있다. 상수 시간 비교로 차단.
	if !ok ||
		subtle.ConstantTimeCompare([]byte(u), []byte(d.user)) != 1 ||
		subtle.ConstantTimeCompare([]byte(p), []byte(d.pass)) != 1 {
		if ok {
			d.logger.Warn("dashboard auth failed", "user", u, "path", r.URL.Path)
		}
		w.Header().Set("WWW-Authenticate", `Basic realm="jirabot dashboard"`)
		http.Error(w, "unauthorized", http.StatusUnauthorized)
		return
	}
	d.proxy.ServeHTTP(w, r)
}
