"""言间 data 侧校验脚本：对 processed/ 候选做结构与一致性检查，输出报告。

只验证生成结果与声明的规则一致，不证明内容质量或发布资格。
用法：python data/scripts/validate.py
"""
from __future__ import annotations

import hashlib
import json
import re
import unicodedata
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DATA = Path(__file__).resolve().parents[1]
PROCESSED = DATA / "processed"
METADATA = DATA / "metadata"
HAN = r"\u3400-\u4dbf\u4e00-\u9fff"
RE_HAN = re.compile(f"[{HAN}]")
RE_LATIN = re.compile(r"[A-Za-z]")
RE_ID = re.compile(r"^official:(word|sentence):[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
SRC_KEYS = ["id", "type", "title", "quote", "locator", "url", "edition",
            "licenseName", "licenseUrl", "attribution", "evidenceStatus", "rightsStatus"]
ENTRY_KEYS = ["id", "recordVersion", "kind", "text", "shortMeaning", "genre", "category",
              "tags", "usageNote", "publicationStatus", "publicationBlockers", "sources"]

errors: list[str] = []
warnings: list[str] = []
stats: Counter = Counter()


def load_jsonl(path: Path):
    rows = []
    with path.open(encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line:
                continue
            try:
                rows.append(json.loads(line))
            except json.JSONDecodeError as exc:
                errors.append(f"{path.name}:{ln} JSON 解析失败：{exc}")
    return rows


def norm_key(s: str) -> str:
    s = unicodedata.normalize("NFC", s)
    return "".join(ch for ch in s if RE_HAN.match(ch) or ch.isdigit())


def check_entry(e, fname: str) -> None:
    for k in ENTRY_KEYS:
        if k not in e:
            errors.append(f"{fname}: 条目缺字段 {k}: {e.get('id')}")
    if not RE_ID.match(e.get("id", "")):
        errors.append(f"{fname}: id 格式不符 official:<kind>:<uuid4>：{e.get('id')}")
    if e.get("publicationStatus") != "candidate":
        errors.append(f"{fname}: 非候选状态 {e.get('publicationStatus')}：{e.get('id')}")
    if e.get("genre") not in ("practical", "classic"):
        errors.append(f"{fname}: genre 不合法 {e.get('genre')}：{e.get('id')}")
    if not isinstance(e.get("tags"), list):
        errors.append(f"{fname}: tags 不是数组：{e.get('id')}")
    if e.get("kind") == "word":
        if len(e["text"]) not in (2, 4):
            errors.append(f"{fname}: 词长异常 {len(e['text'])}：{e['text']}")
        if "definitionEn" not in e:
            errors.append(f"{fname}: 缺 definitionEn：{e['text']}")
        stats["words"] += 1
        if e["shortMeaning"]:
            stats["words_with_gloss"] += 1
            if not e.get("category"):
                warnings.append(f"{fname}: 有释义但无分类：{e['text']}")
        if not e["shortMeaning"] and "missing_short_meaning" not in e["publicationBlockers"]:
            errors.append(f"{fname}: 缺释义未标 blocker：{e['text']}")
    else:
        n = len(RE_HAN.findall(e["text"]))
        if not (8 <= n <= 60):
            errors.append(f"{fname}: 句子汉字数 {n} 越界：{e['text'][:20]}")
        if RE_LATIN.search(e["text"]) or "　" in e["text"]:
            errors.append(f"{fname}: 句子含拉丁字母或全角空格：{e['text'][:20]}")
        if e["shortMeaning"] is not None:
            warnings.append(f"{fname}: 句子带了短释义，需人工确认未捏造：{e['text'][:20]}")
        if e.get("script") == "traditional" and "script_not_simplified" not in e["publicationBlockers"]:
            errors.append(f"{fname}: 繁体条目未标 blocker：{e['text'][:20]}")
        stats["sentences"] += 1
        stats[f"sentences_{e['genre']}"] += 1
    srcs = e.get("sources") or []
    if not srcs:
        errors.append(f"{fname}: 无来源：{e.get('id')}")
    for s in srcs:
        for k in SRC_KEYS:
            if k not in s:
                errors.append(f"{fname}: source 缺字段 {k}：{e.get('id')}")
        if s.get("rightsStatus") not in ("allowed", "project_owned"):
            errors.append(f"{fname}: source 权利状态不可发布 {s.get('rightsStatus')}：{e.get('id')}")
        if s.get("rightsStatus") == "allowed" and not (s.get("licenseName") and s.get("licenseUrl")):
            errors.append(f"{fname}: 外部来源缺许可名/链接：{e.get('id')}")


def main() -> int:
    files = {
        "words": PROCESSED / "entries.words.candidate.jsonl",
        "practical": PROCESSED / "entries.sentences.practical.candidate.jsonl",
        "classic": PROCESSED / "entries.sentences.classic.candidate.jsonl",
    }
    all_entries = []
    for name, path in files.items():
        if not path.exists():
            errors.append(f"缺文件 {path.name}")
            continue
        rows = load_jsonl(path)
        stats[f"file_{name}"] = len(rows)
        for e in rows:
            all_entries.append((name, e))
            check_entry(e, path.name)

    ids = Counter(e["id"] for _, e in all_entries)
    dup_ids = [i for i, c in ids.items() if c > 1]
    if dup_ids:
        errors.append(f"重复 id {len(dup_ids)} 个，例：{dup_ids[:3]}")

    keys = Counter(f"{e['kind']}|{norm_key(e['text'])}" for _, e in all_entries)
    dup_keys = [k for k, c in keys.items() if c > 1]
    if dup_keys:
        errors.append(f"去重键重复 {len(dup_keys)} 个，例：{dup_keys[:3]}")

    # 注册表一致性
    reg_path = METADATA / "id_registry.jsonl"
    if reg_path.exists():
        reg = load_jsonl(reg_path)
        reg_ids = {r["id"] for r in reg}
        missing = [e["id"] for _, e in all_entries if e["id"] not in reg_ids]
        if missing:
            errors.append(f"有 {len(missing)} 条候选 id 未进注册表")
        stats["registry_ids"] = len(reg_ids)
        stats["registry_unused"] = len(reg_ids - set(ids))
    else:
        errors.append("缺 metadata/id_registry.jsonl")

    # manifest 数量一致
    man_path = METADATA / "build_manifest.json"
    if man_path.exists():
        man = json.loads(man_path.read_text(encoding="utf-8"))
        expect = {
            "processed/entries.words.candidate.jsonl": stats["file_words"],
            "processed/entries.sentences.practical.candidate.jsonl": stats["file_practical"],
            "processed/entries.sentences.classic.candidate.jsonl": stats["file_classic"],
        }
        for k, v in expect.items():
            if man["outputs"].get(k) != v:
                errors.append(f"manifest 与文件行数不一致 {k}: {man['outputs'].get(k)} vs {v}")
        stats["contentVersion"] = man["contentVersion"]
    else:
        errors.append("缺 metadata/build_manifest.json")

    # 原始数据哈希（传输完整性）
    idx_path = DATA / "raw" / "index.json"
    if idx_path.exists():
        idx = json.loads(idx_path.read_text(encoding="utf-8"))["entries"]
        ok = bad = 0
        for rec in idx:
            p = DATA / rec["path"]
            if not p.exists():
                if rec["kind"] == "artifact":
                    bad += 1
                    warnings.append(f"raw 产物缺失（已被 .gitignore，可重下）：{rec['path']}")
                continue
            h = hashlib.sha256(p.read_bytes()).hexdigest()
            if h == rec["sha256"]:
                ok += 1
            else:
                bad += 1
                errors.append(f"哈希不符（传输完整性）：{rec['path']}")
        stats["hash_ok"] = ok
        stats["hash_bad"] = bad

    report = {
        "checkedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "result": "PASS" if not errors else "FAIL",
        "errors": errors,
        "warnings": warnings[:50],
        "warningCount": len(warnings),
        "stats": dict(stats),
        "scope": "只校验结构与声明规则一致性；不构成内容质量、许可法务或人工逐条核验结论。",
    }
    (METADATA / "validation_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({k: report[k] for k in ("result", "warningCount")}, ensure_ascii=False))
    print(json.dumps(dict(stats), ensure_ascii=False))
    for e in errors[:20]:
        print("ERROR:", e)
    for w in warnings[:10]:
        print("WARN:", w)
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())