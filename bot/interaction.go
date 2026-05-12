package main

import (
	"io"
	"log/slog"
	"net/http"
)

// InteractionHandler receives Slack interactive payloads (button clicks, menus, etc.)
// and forwards them to the Spring Boot server.
//
// Unlike event callbacks, interaction payloads arrive as application/x-www-form-urlencoded
// with a "payload" form parameter containing JSON. The raw body is forwarded as-is
// so Spring Boot can validate the HMAC signature against the original bytes.
type InteractionHandler struct {
	forwarder *Forwarder
	logger    *slog.Logger
}

func NewInteractionHandler(forwarder *Forwarder, logger *slog.Logger) *InteractionHandler {
	return &InteractionHandler{forwarder: forwarder, logger: logger}
}

func (h *InteractionHandler) ServeHTTP(w http.ResponseWriter, r *http.Request) {
	if r.Method != http.MethodPost {
		w.Header().Set("Allow", http.MethodPost)
		http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
		return
	}

	body, err := io.ReadAll(http.MaxBytesReader(w, r.Body, maxBodyBytes))
	if err != nil {
		h.logger.Warn("failed to read interaction body", "err", err)
		http.Error(w, "bad request", http.StatusBadRequest)
		return
	}

	// Forward raw body + Slack signature headers to Spring Boot.
	// No parsing needed — Spring handles payload extraction and HMAC verification.
	if err := h.forwarder.Forward(r.Context(), body, r.Header); err != nil {
		h.logger.Error("interaction forwarding failed", "err", err)
		http.Error(w, "forwarding failed", http.StatusBadGateway)
		return
	}

	h.logger.Info("interaction forwarded")
	w.WriteHeader(http.StatusOK)
}
