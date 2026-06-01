# Deferred — 진행 중 버튼 클릭 시 브랜치 자동 생성

> **등록일:** 2026-05-18
> **우선순위:** 중간
> **요청자:** 사용자 요청 (이전 대화에서 논의 후 보류 → 재등록)
> **상태(2026-06-01):** ✅ **1차 구현됨 — "Jira 경유" 힌트 방식 (v0.0.4)**

## ✅ 구현된 것 (2026-06-01)

사용자 결정: "git(GitHub API) 직접 연동이 아니라 **Jira를 통해** 브랜치 생성".

기술적 현실: 네이티브 **GitHub for Jira 앱은 create-branch 딥링크를 제공하지 않는다**
([atlassian/github-for-jira#402](https://github.com/atlassian/github-for-jira/issues/402)).
따라서 봇이 자동 생성하지 않고, **"진행 중" 전환 시 다음을 스레드에 안내**한다:
- 이슈 개발 패널 링크 (`{base}/browse/{KEY}`)
- 규칙 기반 **권장 브랜치명** — `BranchNameBuilder` (Bug→`bugfix/`, 그 외→`feature/`, `KEY-요약슬러그`)

실제 브랜치 생성은 사용자가 **Jira 개발 패널 → "브랜치 만들기"** 에서 대상 레포·base 를 선택해 수행
→ 연결된 GitHub 가 생성. **봇은 GitHub 토큰 불필요**, "레포가 작업마다 다름" 블로커도 클릭 시점 선택으로 해소.

구현 위치: `SlackInteractionController.postBranchHint`, `util/BranchNameBuilder` (+테스트).

## 남은(선택) 고도화
- **진짜 원클릭 생성**: GitKraken "Git Integration for Jira"(유료 앱)는 create-branch 딥링크를 제공 →
  그 앱이 연결돼 있으면 딥링크로 모달을 바로 열 수 있음. 현재 Jira-GitHub 연동 종류 확인 필요.
- base branch 기본값/오버라이드, 브랜치 존재 시 처리 등은 Jira UI 가 담당.

---

### (이하 최초 요구사항 기록 — 참고용)

## 배경

"진행 중" 버튼 클릭 시 현재 동작:
1. Jira 상태 전환 (→ 진행 중)
2. 활성 스프린트로 이동

여기에 **Git 브랜치 자동 생성**을 추가하여 Jira 이슈와 브랜치를 연결하려는 기능.

## 요구사항

### 브랜치 명명 규칙
- Jira 기본 형식: `ES2-1948-issue-summary`
- 이슈 타입별 prefix:
  - Story/Task → `feature/ES2-1948-issue-summary`
  - Bug → `bugfix/ES2-1948-issue-summary`

### 기대 동작
1. "진행 중" 버튼 클릭
2. Jira 상태 전환 + 스프린트 이동 (기존)
3. 대상 레포에 브랜치 생성
4. Slack 스레드에 브랜치 생성 결과 알림

## 미결 사항 — 논의 필요

### 대상 레포 결정 문제 (핵심 블로커)
작업에 따라 브랜치가 만들어질 레포가 다름:
- `envector-msa`
- `evi`
- 기타 레포

**옵션:**
1. **이슈 생성 시 레포 선택** — 이슈 등록 단계에서 드롭다운/버튼으로 대상 레포 지정, IssueEntity에 저장
2. **"진행 중" 버튼 클릭 시 레포 선택** — 진행 중 전환 시 레포 선택 모달/버튼 제공
3. **Jira 커스텀 필드** — Jira 이슈에 대상 레포 필드를 두고 그 값을 읽어서 사용
4. **기본 레포 설정 + 수동 오버라이드** — yml에 기본 레포를 설정하고, 필요 시 Slack 명령으로 변경

### 브랜치 생성 방식
- **GitHub API** — `POST /repos/{owner}/{repo}/git/refs`로 생성. 간단하지만 Jira에서 브랜치 트래킹이 안 될 수 있음
- **Jira Development Integration** — Jira가 GitHub 연동되어 있으면 자동 트래킹. GitHub API로 만들어도 브랜치명에 이슈 키가 있으면 Jira가 감지함 (확인 필요)

### 기타 고려사항
- base branch 결정 (main? develop?)
- 브랜치가 이미 존재하는 경우 처리
- GitHub API 인증 토큰 관리 (env var)
- 레포별 권한 확인

## 이전 논의 이력
- 브랜치 명명 규칙은 합의됨 (`feature/` / `bugfix/` prefix)
- **레포가 작업마다 바뀔 수 있어서 보류** (2026-05 초 대화)
- Sonnet으로 브랜치 생성 요청하는 방식은 Jira 트래킹 문제로 기각
