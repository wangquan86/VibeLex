import argparse
from pathlib import Path


DEFAULT_REPOSITORY = "BAAI/bge-reranker-base"
DEFAULT_TARGET = Path(__file__).resolve().parent / "models" / "bge-reranker-base"


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Download a complete local snapshot of the VibeLex reranker model."
    )
    parser.add_argument("--repository", default=DEFAULT_REPOSITORY)
    parser.add_argument("--target", type=Path, default=DEFAULT_TARGET)
    return parser.parse_args()


def main() -> None:
    args = arguments()
    target = args.target.expanduser().resolve()
    target.mkdir(parents=True, exist_ok=True)

    from huggingface_hub import snapshot_download

    snapshot_download(
        repo_id=args.repository,
        local_dir=str(target),
        local_dir_use_symlinks=False,
    )
    if not (target / "config.json").is_file():
        raise RuntimeError("Model snapshot is incomplete; missing config.json")
    weights = list(target.glob("*.safetensors")) + list(target.glob("pytorch_model*.bin"))
    if not weights:
        raise RuntimeError("Model snapshot is incomplete; no model weight file was found")
    print(f"Model downloaded to: {target}")


if __name__ == "__main__":
    main()
