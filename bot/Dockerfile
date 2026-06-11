# Go 봇 이미지 (Slack 이벤트/인터랙션 + Jira webhook 프록시)
FROM golang:1.25-alpine AS build
WORKDIR /src
COPY go.mod go.sum ./
RUN go mod download
COPY . .
RUN CGO_ENABLED=0 go build -o /gobot .

FROM alpine:3
RUN apk add --no-cache ca-certificates wget
COPY --from=build /gobot /usr/local/bin/gobot
EXPOSE 3000
HEALTHCHECK --interval=10s --timeout=3s --retries=5 \
    CMD wget -qO- http://localhost:3000/health || exit 1
ENTRYPOINT ["gobot"]
