package main

import (
	"context"
	"errors"
	"log/slog"
	"net/http"
	"os"
	"os/signal"
	"path/filepath"
	"syscall"
	"time"

	"github.com/joho/godotenv"
)

func main() {
	logger := slog.New(slog.NewJSONHandler(os.Stdout, &slog.HandlerOptions{Level: slog.LevelInfo}))
	slog.SetDefault(logger)

	loadDotenv(logger)

	cfg, err := LoadConfig()
	if err != nil {
		logger.Error("invalid configuration", "err", err)
		os.Exit(1)
	}

	eventForwarder := NewForwarder(cfg.SpringURL, &http.Client{Timeout: cfg.ForwardTimeout}, logger)
	interactionForwarder := NewForwarder(cfg.SpringInteractionURL, &http.Client{Timeout: cfg.ForwardTimeout}, logger)

	handler := NewHandler(eventForwarder, logger)
	interactionHandler := NewInteractionHandler(interactionForwarder, logger)
	jiraWebhookHandler := NewJiraWebhookHandler(cfg.SpringJiraWebhookURL,
		&http.Client{Timeout: cfg.ForwardTimeout}, logger)

	mux := http.NewServeMux()
	mux.Handle("/slack/events", handler)
	mux.Handle("/slack/interactions", interactionHandler)
	// Jira → (ngrok) → 이 봇 → Spring 프록시. 인증(?token=)은 Spring 이 검증한다.
	mux.Handle("/api/jira/webhook", jiraWebhookHandler)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

	// 대시보드를 터널로 노출 (DASHBOARD_USER/PASSWORD 둘 다 있을 때만, Basic Auth).
	// 화이트리스트 경로만 등록한다 — 그 외 Spring API 는 터널에서 계속 404.
	if cfg.DashboardUser != "" && cfg.DashboardPassword != "" {
		dash, err := NewDashboardProxy(cfg.SpringBaseURL, cfg.DashboardUser, cfg.DashboardPassword, logger)
		if err != nil {
			logger.Error("invalid SPRING_BASE_URL", "err", err)
			os.Exit(1)
		}
		for _, p := range []string{
			"/dashboard", "/dashboard/", // 정적 페이지
			"/api/dashboard/",                          // 통계/응답시간/동기화 API
			"/api/user-mappings", "/api/user-mappings/", // 사용자 관리 탭
			"/actuator/health", // 봇 상태 탭의 서버 health 카드
		} {
			mux.Handle(p, dash)
		}
		logger.Info("dashboard proxy enabled", "user", cfg.DashboardUser)
	}

	server := &http.Server{
		Addr:              ":" + cfg.Port,
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	serverErr := make(chan error, 1)
	go func() {
		logger.Info("bot listening", "addr", server.Addr, "events_to", cfg.SpringURL, "interactions_to", cfg.SpringInteractionURL)
		if err := server.ListenAndServe(); err != nil && !errors.Is(err, http.ErrServerClosed) {
			serverErr <- err
		}
		close(serverErr)
	}()

	select {
	case <-ctx.Done():
		logger.Info("shutdown signal received, draining")
	case err := <-serverErr:
		if err != nil {
			logger.Error("server error", "err", err)
			os.Exit(1)
		}
		return
	}

	shutdownCtx, cancel := context.WithTimeout(context.Background(), 10*time.Second)
	defer cancel()
	if err := server.Shutdown(shutdownCtx); err != nil {
		logger.Error("graceful shutdown failed", "err", err)
		os.Exit(1)
	}
	logger.Info("shutdown complete")
}

// loadDotenv loads the repo-root .env when present; missing file is not fatal.
// STUDY(go): filepath.Join + relative ../.env works because the bot is expected
// to run from the bot/ directory per README.
func loadDotenv(logger *slog.Logger) {
	candidates := []string{".env", filepath.Join("..", ".env")}
	for _, path := range candidates {
		if _, err := os.Stat(path); err == nil {
			if err := godotenv.Load(path); err != nil {
				logger.Warn("failed to load .env", "path", path, "err", err)
				return
			}
			logger.Info(".env loaded", "path", path)
			return
		}
	}
}
