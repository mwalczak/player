FROM python:3.12-alpine
WORKDIR /app
COPY sync_server.py .
ENV PORT=8080 SYNC_DB=/data/sync.db PYTHONUNBUFFERED=1
EXPOSE 8080
CMD ["python3", "sync_server.py"]
