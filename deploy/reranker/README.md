# VibeLex CPU Reranker

This directory contains a standalone CPU reranker service. The model and virtual environment are
intentionally excluded from version control.

## Local Windows setup

Run the following commands from `deploy/reranker` with Python 3.12:

```powershell
python -m venv .venv
.\.venv\Scripts\python -m pip install -r requirements.txt
.\.venv\Scripts\python download_model.py
.\.venv\Scripts\python -m uvicorn app:app --host 127.0.0.1 --port 8082 --workers 1
```

The model is downloaded as ordinary files under `models/bge-reranker-base`; no Hugging Face cache
symlinks are required. In a second terminal, run:

```powershell
.\.venv\Scripts\python smoke_test.py
```

Useful environment variables for a local performance test:

```powershell
$env:RERANKER_BATCH_SIZE = "4"
$env:RERANKER_THREADS = "4"
```

## Transfer to the CentOS server

Stop the local service and archive this directory without `.venv` or `__pycache__`, but include the
downloaded `models` directory. Extract it on the server as `/opt/vibelex-reranker`.

Create the server virtual environment with the independent Python installation:

```bash
/opt/python3.12/bin/python3.12 -m venv /opt/vibelex-reranker/venv
/opt/vibelex-reranker/venv/bin/python -m pip install -r /opt/vibelex-reranker/requirements.txt
```

Create the service account and permissions, then install the supplied unit:

```bash
useradd -r -s /sbin/nologin -d /opt/vibelex-reranker vibelex-reranker
chown -R vibelex-reranker:vibelex-reranker /opt/vibelex-reranker
cp /opt/vibelex-reranker/vibelex-reranker.service /etc/systemd/system/
systemctl daemon-reload
systemctl enable --now vibelex-reranker
```

Verify startup and run the same smoke test:

```bash
journalctl -u vibelex-reranker -f
curl http://127.0.0.1:8082/health
/opt/vibelex-reranker/venv/bin/python /opt/vibelex-reranker/smoke_test.py
```

The service binds to the private address `10.145.12.11:8082`. Restrict that port to the VibeLex
application server; the reranker service does not implement public-network authentication.

