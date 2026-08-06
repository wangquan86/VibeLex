import os
import threading
from contextlib import asynccontextmanager
from pathlib import Path

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel
from transformers import AutoModelForSequenceClassification, AutoTokenizer


BASE_DIR = Path(__file__).resolve().parent
DEFAULT_MODEL = BASE_DIR / "models" / "bge-reranker-base"
MODEL = os.getenv("RERANKER_MODEL", str(DEFAULT_MODEL))
MAX_TEXTS = int(os.getenv("RERANKER_MAX_TEXTS", "32"))
MAX_QUERY_CHARACTERS = int(os.getenv("RERANKER_MAX_QUERY_CHARACTERS", "2000"))
MAX_TEXT_CHARACTERS = int(os.getenv("RERANKER_MAX_TEXT_CHARACTERS", "4000"))
MAX_LENGTH = int(os.getenv("RERANKER_MAX_LENGTH", "512"))
BATCH_SIZE = int(os.getenv("RERANKER_BATCH_SIZE", "4"))
THREADS = int(os.getenv("RERANKER_THREADS", "4"))
LOCAL_FILES_ONLY = os.getenv("RERANKER_LOCAL_FILES_ONLY", "true").lower() == "true"

torch.set_num_threads(THREADS)
torch.set_num_interop_threads(1)


class RerankRequest(BaseModel):
    query: str
    texts: list[str]
    return_text: bool = False


class Reranker:
    def __init__(self) -> None:
        self.tokenizer = AutoTokenizer.from_pretrained(
            MODEL, local_files_only=LOCAL_FILES_ONLY, trust_remote_code=False
        )
        self.model = AutoModelForSequenceClassification.from_pretrained(
            MODEL, local_files_only=LOCAL_FILES_ONLY, trust_remote_code=False
        )
        self.model.eval()
        self.model.to("cpu")
        self.lock = threading.Lock()

    def score(self, query: str, texts: list[str]) -> list[float]:
        scores: list[float] = []
        with self.lock, torch.inference_mode():
            for start in range(0, len(texts), BATCH_SIZE):
                batch = texts[start : start + BATCH_SIZE]
                inputs = self.tokenizer(
                    [query] * len(batch),
                    batch,
                    padding=True,
                    truncation=True,
                    max_length=MAX_LENGTH,
                    return_tensors="pt",
                )
                logits = self.model(**inputs, return_dict=True).logits.view(-1).float()
                scores.extend(torch.sigmoid(logits).tolist())
        return scores


runtime: Reranker | None = None


@asynccontextmanager
async def lifespan(_: FastAPI):
    global runtime
    runtime = Reranker()
    yield
    runtime = None


app = FastAPI(title="VibeLex CPU Reranker", version="1.0", lifespan=lifespan)


@app.get("/health")
def health() -> dict[str, object]:
    return {
        "status": "ok" if runtime is not None else "starting",
        "model": MODEL,
        "device": "cpu",
        "threads": THREADS,
    }


@app.post("/rerank")
def rerank(request: RerankRequest) -> dict[str, object]:
    query = request.query.strip()
    if not query:
        raise HTTPException(status_code=400, detail="query must not be blank")
    if len(query) > MAX_QUERY_CHARACTERS:
        raise HTTPException(status_code=400, detail="query is too long")
    if not request.texts or len(request.texts) > MAX_TEXTS:
        raise HTTPException(status_code=400, detail=f"texts must contain 1 to {MAX_TEXTS} items")

    texts = [text.strip() for text in request.texts]
    if any(not text for text in texts):
        raise HTTPException(status_code=400, detail="candidate text must not be blank")
    if any(len(text) > MAX_TEXT_CHARACTERS for text in texts):
        raise HTTPException(status_code=400, detail="candidate text is too long")
    if runtime is None:
        raise HTTPException(status_code=503, detail="reranker is not ready")

    scores = runtime.score(query, texts)
    results = [
        {
            "index": index,
            "score": round(float(score), 8),
            **({"text": texts[index]} if request.return_text else {}),
        }
        for index, score in enumerate(scores)
    ]
    results.sort(key=lambda item: (-item["score"], item["index"]))
    return {"model": MODEL, "results": results}

