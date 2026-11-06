# SBOM 자동 수정 에이전트 이미지 (v0.0.94). 봇(SbomFixService)이 레포 작업 트리(.git 없음)만 /work 에 마운트하고
# CLAUDE_CODE_OAUTH_TOKEN 하나만 넘겨 `claude -p` 로 의존성을 고치게 한다. GitHub 토큰·.env 는 들어오지 않는다.
# 대상 레포들이 Go 1.26 계열이라 같은 계열의 최신 패치를 넣고, 더 높은 go.mod 요구는 GOTOOLCHAIN=auto 가 받는다.
FROM node:24-bookworm-slim

ARG GO_VERSION=1.26.10
ARG GO_SHA256=ccfe7ae822d2c2f41f4fe7c956199967a4dac22d0ee0d2b2bfdb440aab3b20c5

RUN apt-get update \
    && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends ca-certificates curl git jq \
    && rm -rf /var/lib/apt/lists/* \
    && curl -fsSL -o /tmp/go.tgz "https://go.dev/dl/go${GO_VERSION}.linux-amd64.tar.gz" \
    && echo "${GO_SHA256}  /tmp/go.tgz" | sha256sum -c - \
    && tar -C /usr/local -xzf /tmp/go.tgz && rm /tmp/go.tgz \
    && npm install -g @anthropic-ai/claude-code@latest \
    && npm cache clean --force

ENV PATH=/usr/local/go/bin:$PATH \
    CLAUDE_CODE_DISABLE_NONESSENTIAL_TRAFFIC=1

# node 이미지의 기본 사용자(uid 1000) — 봇이 docker run --user 로 호스트 uid 를 다시 지정한다.
USER node
WORKDIR /work
