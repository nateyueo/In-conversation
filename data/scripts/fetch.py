"""言间 data 侧采集脚本（有界、可重跑）。

职责：按 sources.json 声明的 raw 产物与许可证据做定向下载/本地复用，
计算 sha256、字节数与行数，写入 data/raw/index.json 作为传输完整性记录。
不写业务文案，不修改 raw/evidence 之外的文件。

用法：
  python data/scripts/fetch.py --scratch-dir <会话scratch>   # 全部（哈希一致的跳过）
  python data/scripts/fetch.py --only tatoeba
  python data/scripts/fetch.py --reuse cc-cedict=<本地gz路径>
  python data/scripts/fetch.py --skip-network                # 只校验已有文件
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import json
import shutil
import sys
import urllib.error
import urllib.request
from datetime import datetime, timezone
from pathlib import Path

DATA = Path(__file__).resolve().parents[1]
RAW_INDEX = DATA / "raw" / "index.json"
UA = "yanjian-content-prep/1.0 (offline study app; bounded fetch)"
MAX_BYTES = 96 * 1024 * 1024


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def sha256_file(path: Path) -> str:
    h = hashlib.sha256()
    with path.open("rb") as f:
        for chunk in iter(lambda: f.read(1 << 20), b""):
            h.update(chunk)
    return h.hexdigest()


def count_lines(path: Path) -> int | None:
    name = path.name.lower()
    try:
        if name.endswith(".gz"):
            with gzip.open(path, "rb") as f:
                return sum(1 for _ in f)
        if name.endswith(".bz2"):
            with bz2.open(path, "rb") as f:
                return sum(1 for _ in f)
        if name.endswith((".txt", ".tsv", ".jsonl", ".json")):
            with path.open("rb") as f:
                return sum(1 for _ in f)
    except Exception:
        return None
    return None


def download(url: str, dest: Path, timeout: int) -> int:
    dest.parent.mkdir(parents=True, exist_ok=True)
    tmp = dest.with_suffix(dest.suffix + ".part")
    req = urllib.request.Request(url, headers={"User-Agent": UA, "Accept": "*/*"})
    with urllib.request.urlopen(req, timeout=timeout) as resp:
        status = resp.status
        total = 0
        with tmp.open("wb") as out:
            while True:
                chunk = resp.read(1 << 20)
                if not chunk:
                    break
                total += len(chunk)
                if total > MAX_BYTES:
                    out.close()
                    tmp.unlink(missing_ok=True)
                    raise RuntimeError(f"exceeded MAX_BYTES for {url}")
                out.write(chunk)
    tmp.replace(dest)
    return status


def load_index() -> dict:
    if RAW_INDEX.exists():
        return json.loads(RAW_INDEX.read_text(encoding="utf-8"))
    return {"note": "raw/evidence 传输完整性索引（由 fetch.py 生成）", "entries": []}


def record(index: dict, kind: str, source_id: str, title: str, url: str,
           rel_path: str, path: Path, mode: str, http_status) -> dict:
    entry = {
        "kind": kind,
        "sourceId": source_id,
        "title": title,
        "url": url,
        "path": rel_path,
        "mode": mode,
        "httpStatus": http_status,
        "sha256": sha256_file(path),
        "bytes": path.stat().st_size,
        "lineCount": count_lines(path),
        "fetchedAt": now_iso(),
    }
    index["entries"] = [e for e in index["entries"]
                        if not (e["kind"] == kind and e["path"] == rel_path)]
    index["entries"].append(entry)
    index["entries"].sort(key=lambda e: (e["kind"], e["sourceId"], e["path"]))
    return entry


def main(argv: list[str]) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--only", nargs="*", default=None, help="只处理这些 source id")
    ap.add_argument("--reuse", nargs="*", default=[], help="sourceid=本地文件路径，复用已下载文件")
    ap.add_argument("--skip-network", action="store_true")
    ap.add_argument("--timeout", type=int, default=120)
    ap.add_argument("--scratch-dir", default=None, help="本会话 scratch 目录，用于复用早前抓取的证据文件")
    args = ap.parse_args(argv)

    reuse: dict[str, Path] = {}
    for spec in args.reuse:
        sid, _, p = spec.partition("=")
        reuse[sid] = Path(p)
    scratch = Path(args.scratch_dir) if args.scratch_dir else None

    sources = json.loads((DATA / "sources.json").read_text(encoding="utf-8"))["sources"]
    index = load_index()
    problems: list[str] = []

    for src in sources:
        sid = src["id"]
        if args.only and sid not in args.only:
            continue

        for art in src.get("artifacts") or []:
            dest = DATA / art["rawPath"]
            declared = art.get("sha256")
            mode, status = "existing", None
            if dest.exists() and (declared is None or sha256_file(dest) == declared):
                pass
            elif sid in reuse and reuse[sid].is_file():
                dest.parent.mkdir(parents=True, exist_ok=True)
                shutil.copyfile(reuse[sid], dest)
                mode = "reuse-local"
            elif args.skip_network:
                problems.append(f"missing artifact {sid}/{art['name']} (skip-network)")
                continue
            else:
                try:
                    status = download(art["url"], dest, args.timeout)
                    mode = "download"
                except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError, TimeoutError, OSError) as exc:
                    problems.append(f"download failed {sid}/{art['name']}: {exc}")
                    continue
            actual = sha256_file(dest)
            if declared and actual != declared:
                problems.append(f"sha mismatch {sid}/{art['name']}: declared {declared} actual {actual}")
            e = record(index, "artifact", sid, art["name"], art["url"], art["rawPath"], dest, mode, status)
            print(f"[artifact] {sid:16s} {art['name']:32s} {e['bytes']:>10d}B mode={mode} sha256={e['sha256'][:16]}")

        for ev in src.get("evidence") or []:
            dest = DATA / ev["outPath"]
            mode, status = "existing", None
            if dest.exists():
                pass
            else:
                cached = scratch / ev["reuseScratchFile"] if (scratch and ev.get("reuseScratchFile")) else None
                if cached and cached.is_file():
                    dest.parent.mkdir(parents=True, exist_ok=True)
                    shutil.copyfile(cached, dest)
                    mode = "reuse-scratch"
                elif args.skip_network:
                    problems.append(f"missing evidence {ev['outPath']} (skip-network)")
                    continue
                else:
                    try:
                        status = download(ev["url"], dest, args.timeout)
                        mode = "download"
                    except (urllib.error.URLError, urllib.error.HTTPError, RuntimeError, TimeoutError, OSError) as exc:
                        problems.append(f"evidence failed {sid}/{ev['outPath']}: {exc}")
                        continue
            e = record(index, "evidence", sid, ev["title"], ev["url"], ev["outPath"], dest, mode, status)
            print(f"[evidence] {sid:16s} {ev['outPath']:44s} {e['bytes']:>8d}B mode={mode}")

    RAW_INDEX.parent.mkdir(parents=True, exist_ok=True)
    RAW_INDEX.write_text(json.dumps(index, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"\nindex entries: {len(index['entries'])}")
    if problems:
        print("\n问题（未静默忽略）：")
        for p in problems:
            print(" -", p)
        return 1
    return 0


if __name__ == "__main__":
    raise SystemExit(main(sys.argv[1:]))