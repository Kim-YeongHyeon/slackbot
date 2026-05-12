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

	mux := http.NewServeMux()
	mux.Handle("/slack/events", handler)
	mux.Handle("/slack/interactions", interactionHandler)
	mux.HandleFunc("/health", func(w http.ResponseWriter, r *http.Request) {
		w.WriteHeader(http.StatusOK)
		_, _ = w.Write([]byte("ok"))
	})

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
