# 문서 검색 사이드카 — 임베딩 모델(BAAI/bge-m3)과 조각 원문을 메모리에 올려 두고 봇의 질문에 답한다.
FROM python:3.12-slim

ENV PIP_NO_CACHE_DIR=1 PIP_DISABLE_PIP_VERSION_CHECK=1 PYTHONUNBUFFERED=1 PYTHONDONTWRITEBYTECODE=1 \
    HF_HUB_DISABLE_TELEMETRY=1 TOKENIZERS_PARALLELISM=false

WORKDIR /srv
RUN pip install --index-url https://download.pytorch.org/whl/cpu torch==2.15.0
COPY requirements.txt .
RUN pip install -r requirements.txt
COPY app ./app

EXPOSE 8090
CMD ["python", "-m", "app.server"]
