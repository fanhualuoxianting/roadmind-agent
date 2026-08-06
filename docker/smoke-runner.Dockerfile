FROM python:3.11.9-slim-bookworm

RUN apt-get update \
    && apt-get install --no-install-recommends -y bash curl \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /workspace

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1
