import argparse
import json
from urllib.request import Request, urlopen


def arguments() -> argparse.Namespace:
    parser = argparse.ArgumentParser(description="Run a VibeLex reranker smoke test.")
    parser.add_argument("--endpoint", default="http://127.0.0.1:8082/rerank")
    return parser.parse_args()


def main() -> None:
    args = arguments()
    payload = {
        "query": "主角连续尝试三次都失败了，嘴上说没事，转身后却坐在台阶上怀疑人生。",
        "texts": [
            "词条：人生无常大肠包小肠。释义：用幽默方式调侃人生中的各种不如意。",
            "词条：失败的man。释义：用于调侃某人或某件事没有达到预期。",
            "词条：上天台。释义：准备跳楼，特指股市大跌时。",
            "词条：整段垮掉。释义：形容一段表现完全失败，常用于尴尬、自嘲和调侃。",
        ],
        "return_text": True,
    }
    request = Request(
        args.endpoint,
        data=json.dumps(payload, ensure_ascii=False).encode("utf-8"),
        headers={"Content-Type": "application/json"},
        method="POST",
    )
    with urlopen(request, timeout=30) as response:
        print(json.dumps(json.load(response), ensure_ascii=False, indent=2))


if __name__ == "__main__":
    main()

