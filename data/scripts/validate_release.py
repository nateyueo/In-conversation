"""言间正式内容包校验（严格对齐发布契约）。

只检查结构与声明规则：格式字段、条目字段集合、枚举、id 唯一与注册表一致、
来源完整、许可字段、文本形态。不证明内容质量与法务结论。
用法：python data/scripts/validate_release.py
"""
from __future__ import annotations

import gzip
import json
import re
from collections import Counter
from datetime import datetime, timezone
from pathlib import Path

DATA = Path(__file__).resolve().parents[1]
PKG = DATA / "processed" / "official-content.json"
REPORT = DATA / "metadata" / "release_validation.json"

TOP_KEYS = {"format", "schemaVersion", "datasetId", "contentVersion", "entries"}
ENTRY_KEYS = ["id", "recordVersion", "kind", "text", "shortMeaning", "genre", "category",
              "tags", "usageNote", "publicationStatus", "sources"]
SRC_KEYS = ["id", "type", "title", "quote", "locator", "url", "edition", "licenseName",
            "licenseUrl", "attribution", "evidenceStatus", "rightsStatus"]
SRC_TYPES = {"dictionary", "community_sentence", "public_domain_text", "original", "film"}
RIGHTS = {"open_license", "public_domain", "original"}
EVIDENCE = {"source_checked", "verified"}
GENRES = {"practical", "classic"}
KINDS = {"word", "sentence"}
RE_ID = re.compile(r"^official:(word|sentence):[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-"
                   r"[89ab][0-9a-f]{3}-[0-9a-f]{12}$")
RE_HAN = re.compile(r"[\u3400-\u4dbf\u4e00-\u9fff]")
RE_LATIN = re.compile(r"[A-Za-z]")
SENTENCE_GLOSS_MIN_LEN, SENTENCE_GLOSS_MAX_LEN = 6, 60
SENTENCE_GLOSS_MIN_RECORD_VERSION = 2

errors: list[str] = []
warnings: list[str] = []


def load_registry() -> set[str]:
    ids = set()
    for path in (DATA / "metadata" / "id_registry.jsonl.gz", DATA / "metadata" / "id_registry.jsonl"):
        if not path.exists():
            continue
        opener = gzip.open if path.suffix == ".gz" else open
        with opener(path, "rt", encoding="utf-8") as f:
            for line in f:
                if line.strip():
                    ids.add(json.loads(line)["id"])
    return ids


def main() -> int:
    if not PKG.exists():
        print("缺正式包", PKG)
        return 1
    raw = PKG.read_bytes()
    pkg = json.loads(raw.decode("utf-8"))

    if set(pkg) != TOP_KEYS:
        errors.append(f"顶层字段不符：{sorted(set(pkg) ^ TOP_KEYS)}")
    if pkg.get("format") != "yanjian-official-content":
        errors.append(f"format 不符：{pkg.get('format')}")
    if pkg.get("datasetId") != "yanjian-text":
        errors.append(f"datasetId 不符：{pkg.get('datasetId')}")
    if pkg.get("schemaVersion") != 1 or not isinstance(pkg.get("schemaVersion"), int):
        errors.append(f"schemaVersion 必须是整数 1：{pkg.get('schemaVersion')}")
    if pkg.get("contentVersion") != 5 or not isinstance(pkg.get("contentVersion"), int):
        errors.append(f"contentVersion 必须是整数 5（全量句意及古典白话修订）：{pkg.get('contentVersion')}")

    entries = pkg.get("entries") or []
    stats: Counter = Counter()
    ids = Counter()
    cats = Counter()
    genres = Counter()
    tags = Counter()
    by_rule: Counter = Counter()

    for e in entries:
        miss = [k for k in ENTRY_KEYS if k not in e]
        extra = [k for k in e if k not in ENTRY_KEYS]
        if miss or extra:
            errors.append(f"条目字段不符 {miss}{extra}：{e.get('id')}")
            continue
        ids[e["id"]] += 1
        if not RE_ID.match(e["id"]):
            errors.append(f"id 格式不符：{e['id']}")
        if not isinstance(e["recordVersion"], int) or e["recordVersion"] < 1:
            errors.append(f"recordVersion 必须是 ≥1 的整数：{e['id']}")
        if e["kind"] not in KINDS:
            errors.append(f"kind 不合法：{e['id']}")
        if e["genre"] not in GENRES:
            errors.append(f"genre 不合法：{e['id']}")
        if e["publicationStatus"] != "published":
            errors.append(f"publicationStatus 应为 published：{e['id']}")
        if not e["category"] or not isinstance(e["category"], str):
            errors.append(f"category 为空：{e['id']}")
        if not isinstance(e["tags"], list) or not e["tags"]:
            errors.append(f"tags 必须是非空数组：{e['id']}")
        for t in e["tags"]:
            tags[t] += 1
        if not any(t in ("daily", "writing") for t in e["tags"]):
            errors.append(f"tags 缺 daily/writing：{e['id']}")
        if not isinstance(e["usageNote"], str):
            errors.append(f"usageNote 必须是字符串：{e['id']}")
        text = e["text"]
        if not text or not isinstance(text, str):
            errors.append(f"text 为空：{e['id']}")
            continue
        n_han = len(RE_HAN.findall(text))
        if e["kind"] == "word":
            stats["words"] += 1
            genres[f"word:{e['genre']}"] += 1
            if len(text) not in (2, 4):
                errors.append(f"词长异常：{text}")
            if not e["shortMeaning"] or not e["shortMeaning"].strip():
                errors.append(f"词语缺中文释义：{text}")
            elif len(e["shortMeaning"]) > 60:
                by_rule["word_gloss_too_long"] += 1
            if RE_LATIN.search(text):
                errors.append(f"词含拉丁字母：{text}")
        else:
            stats["sentences"] += 1
            genres[f"sentence:{e['genre']}"] += 1
            if not (8 <= n_han <= 60):
                errors.append(f"句子汉字数 {n_han} 越界：{text[:16]}")
            meaning = e["shortMeaning"]
            if not isinstance(meaning, str):
                errors.append(f"句子 shortMeaning 必须是字符串：{e['id']}")
            elif meaning == "":
                stats["sentencesWithoutMeaning"] += 1
                errors.append(f"v4 句子仍缺释义：{e['id']}")
            elif not (SENTENCE_GLOSS_MIN_LEN <= len(meaning) <= SENTENCE_GLOSS_MAX_LEN):
                errors.append(f"句子短释义长度异常（{len(meaning)}）：{e['id']}")
            elif RE_LATIN.search(meaning):
                errors.append(f"句子短释义含拉丁字母：{e['id']}")
            else:
                stats["sentencesWithMeaning"] += 1
            if RE_LATIN.search(text):
                errors.append(f"句子含拉丁字母：{text[:16]}")
        cats[e["category"]] += 1

        srcs = e["sources"]
        if not isinstance(srcs, list) or not srcs:
            errors.append(f"sources 为空：{e['id']}")
        for s in srcs:
            if set(s) != set(SRC_KEYS):
                errors.append(f"source 字段不符：{e['id']}")
                continue
            if s["type"] not in SRC_TYPES:
                errors.append(f"source.type 不合法：{s['type']}")
            if s["rightsStatus"] not in RIGHTS:
                errors.append(f"source.rightsStatus 不合法：{s['rightsStatus']}")
            if s["evidenceStatus"] not in EVIDENCE:
                errors.append(f"source.evidenceStatus 不合法：{s['evidenceStatus']}")
            if not s["licenseName"]:
                errors.append(f"source 缺 licenseName：{e['id']}")
            if not s["attribution"]:
                errors.append(f"source 缺 attribution：{e['id']}")
            if s["type"] == "original":
                if s["rightsStatus"] != "original":
                    errors.append(f"原创来源 rightsStatus 应为 original：{e['id']}")
            elif s["rightsStatus"] == "open_license" and not s["licenseUrl"]:
                errors.append(f"开放许可来源缺 licenseUrl：{e['id']}")
            if s["type"] == "film":
                errors.append(f"本轮不应有 film 来源：{e['id']}")
        for s in srcs:
            if s["type"] == "community_sentence" and not s["id"].startswith("tatoeba:"):
                errors.append(f"community_sentence 来源 id 缺原 sentenceId：{s['id']}")
        if e["kind"] == "sentence" and e["shortMeaning"]:
            if not any(s["type"] == "original" for s in srcs):
                errors.append(f"句子有短释义但缺原创来源：{e['id']}")

    dup = [i for i, c in ids.items() if c > 1]
    if dup:
        errors.append(f"重复 id {len(dup)} 个，例：{dup[:3]}")

    # 校改表与包内条目一致性（ID 不变、正文已改、recordVersion 已升）
    corr_path = DATA / "corrections" / "text-corrections.psv"
    corrections_checked = 0
    if corr_path.exists():
        by_id = {e["id"]: e for e in entries}
        for ln, line in enumerate(corr_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            cols = [c.strip() for c in line.split("|")]
            if len(cols) != 8:
                errors.append(f"校改表第 {ln} 行列数不是 8")
                continue
            sid, src_id, old_text, new_text, rec_ver = cols[0], cols[1], cols[2], cols[3], int(cols[4])
            e = by_id.get(sid)
            if e is None:
                errors.append(f"校改目标 id 不在包内：{sid}")
                continue
            if e["text"] != new_text:
                errors.append(f"校改未生效（正文不是 newText）：{sid}")
            if old_text in e["text"]:
                errors.append(f"校改后正文仍含 oldText：{sid}")
            if e["recordVersion"] < rec_ver:
                errors.append(f"recordVersion 未升到 {rec_ver}：{sid}")
            if src_id and e["sources"][0]["id"] != src_id:
                errors.append(f"校改来源不匹配：{sid}")
            corrections_checked += 1

    # 句子短释义表与包内条目一致性（原文未改、释义已生效、recordVersion 已升、附原创来源）
    gloss_path = DATA / "editorial" / "sentences.psv"
    glosses_checked = 0
    if gloss_path.exists():
        by_id = {e["id"]: e for e in entries}
        for ln, line in enumerate(gloss_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            cols = [c.strip() for c in line.split("|")]
            if len(cols) != 4:
                errors.append(f"句释义表第 {ln} 行列数不是 4")
                continue
            sid, text, meaning = cols[0], cols[1], cols[2]
            e = by_id.get(sid)
            if e is None:
                errors.append(f"句释义目标 id 不在包内：{sid}")
                continue
            if e["kind"] != "sentence":
                errors.append(f"句释义目标不是句子：{sid}")
                continue
            if e["text"] != text:
                errors.append(f"句释义表原句与包内正文不一致：{sid}")
            if e["shortMeaning"] != meaning:
                errors.append(f"句释义未生效：{sid}")
            if e["recordVersion"] < SENTENCE_GLOSS_MIN_RECORD_VERSION:
                errors.append(f"句释义已挂但 recordVersion 未升：{sid}")
            if not any(s["type"] == "original" for s in e["sources"]):
                errors.append(f"句释义缺原创来源：{sid}")
            glosses_checked += 1

    reg_ids = load_registry()
    revision_path = DATA / "editorial" / "sentence-gloss-revisions.psv"
    seen_revisions = set()
    if revision_path.exists():
        by_id = {e["id"]: e for e in entries}
        for ln, line in enumerate(revision_path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("|")
            if len(fields) != 2 or not fields[1].isdigit() or int(fields[1]) < 3 or fields[0] in seen_revisions:
                errors.append(f"释义修订表第 {ln} 行无效或重复")
                continue
            sid, revision = fields[0], int(fields[1])
            seen_revisions.add(sid)
            e = by_id.get(sid)
            if e is None or e["kind"] != "sentence" or e["recordVersion"] != revision:
                errors.append(f"释义修订号未生效：{sid}")

    unknown = [i for i in ids if i not in reg_ids]
    if unknown:
        errors.append(f"{len(unknown)} 个 id 未在稳定注册表中，例：{unknown[:3]}")

    report = {
        "checkedAt": datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "result": "PASS" if not errors else "FAIL",
        "errors": errors,
        "warnings": warnings,
        "package": {
            "path": "data/processed/official-content.json",
            "bytes": len(raw),
            "entries": len(entries),
            "words": stats["words"],
            "sentences": stats["sentences"],
            "sentencesWithMeaning": stats["sentencesWithMeaning"],
            "sentencesWithoutMeaning": stats["sentencesWithoutMeaning"],
        },
        "genreCounts": dict(sorted(genres.items())),
        "categoryCounts": dict(cats.most_common()),
        "tagCounts": dict(tags.most_common()),
        "registryIds": len(reg_ids),
        "contentVersion": pkg.get("contentVersion"),
        "correctionsChecked": corrections_checked,
        "sentenceGlossesChecked": glosses_checked,
    }
    REPORT.write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(json.dumps({"result": report["result"], "errors": len(errors),
                      "package": report["package"]}, ensure_ascii=False))
    print(json.dumps(report["genreCounts"], ensure_ascii=False))
    for e in errors[:15]:
        print("ERROR:", e)
    return 0 if not errors else 1


if __name__ == "__main__":
    raise SystemExit(main())