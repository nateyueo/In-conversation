"""Validate DeepSeek-authored batches, then explicitly merge complete coverage into PSV."""
from __future__ import annotations
import argparse
import collections
import datetime
import json
from pathlib import Path
import random
import re

ROOT = Path(__file__).resolve().parents[2]
DATA = ROOT / "data"
BATCHES = DATA / "editorial/sentence-gloss-batches"
PSV = DATA / "editorial/sentences.psv"


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--apply", action="store_true", help="Merge only when every missing sentence is covered")
    parser.add_argument("--sample", type=Path, help="Write a 50-row stratified random sample for manual review")
    parser.add_argument("--seed", type=int, default=20261024)
    args = parser.parse_args()
    package = json.loads((DATA / "processed/official-content.json").read_text(encoding="utf-8"))
    entries = {entry["id"]: entry for entry in package["entries"]}
    correction_path = DATA / "corrections/text-corrections.psv"
    if correction_path.exists():
        for line in correction_path.read_text(encoding="utf-8").splitlines():
            if not line.strip() or line.startswith("#"):
                continue
            sid, source_id, old_text, new_text, *_ = line.split("|")
            if sid not in entries or not any(source["id"] == source_id for source in entries[sid]["sources"]):
                raise SystemExit(f"Correction target/source mismatch: {sid}")
            if entries[sid]["text"] not in (old_text, new_text):
                raise SystemExit(f"Correction original text mismatch: {sid}")
            entries[sid]["text"] = new_text
    original = PSV.read_text(encoding="utf-8")
    existing = {}
    for line in original.splitlines():
        if line.strip() and not line.startswith("#"):
            sid, text, meaning, authored = line.split("|")
            if sid in existing:
                raise SystemExit(f"Duplicate existing ID: {sid}")
            existing[sid] = {"id": sid, "text": text, "shortMeaning": meaning, "authoredOn": authored}
    target = {sid for sid, e in entries.items() if e["kind"] == "sentence" and sid not in existing}
    rows = {}
    errors = []
    for path in sorted(BATCHES.rglob("*.jsonl")):
        if path.name == "uncertainties.jsonl":
            continue
        for number, line in enumerate(path.read_text(encoding="utf-8-sig").splitlines(), 1):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
                if set(row) != {"id", "text", "shortMeaning", "authoredOn"}:
                    raise ValueError("Expected id,text,shortMeaning,authoredOn")
                if not all(isinstance(value, str) for value in row.values()):
                    raise ValueError("All fields must be strings")
                sid = row["id"]
                if sid in rows:
                    raise ValueError(f"Duplicate batch ID {sid}")
                if sid not in entries or entries[sid]["kind"] != "sentence":
                    raise ValueError(f"Unknown sentence ID {sid}")
                if sid in existing and row != existing[sid]:
                    raise ValueError(f"Would overwrite existing editorial row {sid}")
                if row["text"] != entries[sid]["text"]:
                    raise ValueError(f"Original text mismatch {sid}")
                gloss = row["shortMeaning"]
                if gloss != gloss.strip() or not 6 <= len(gloss) <= 60:
                    raise ValueError(f"Invalid meaning length/whitespace {sid}")
                if re.search(r"[A-Za-z|\r\n]", gloss) or not re.search(r"[\u3400-\u9fff]", gloss):
                    raise ValueError(f"Invalid meaning characters {sid}")
                if gloss == row["text"] or any(term in gloss for term in ("释义待补充", "暂无释义", "无法解释", "这句话表达了", "这句话体现了")):
                    raise ValueError(f"Copied text or placeholder {sid}")
                if any("|" in value or "\n" in value or "\r" in value for value in row.values()):
                    raise ValueError(f"PSV delimiter in field {sid}")
                datetime.date.fromisoformat(row["authoredOn"])
                rows[sid] = row
            except (ValueError, KeyError, TypeError) as exc:
                errors.append(f"{path.relative_to(ROOT)}:{number}: {exc}")
    missing = target - rows.keys()
    new_rows = {sid: row for sid, row in rows.items() if sid in target}
    repeats = collections.Counter(row["shortMeaning"] for row in new_rows.values())
    summary = {"existing": len(existing), "new": len(new_rows), "missing": len(missing),
               "byGenre": dict(collections.Counter(entries[sid]["genre"] for sid in new_rows)),
               "repeatedMeanings": sum(n > 1 for n in repeats.values()), "errors": errors[:30], "errorCount": len(errors)}
    print(json.dumps(summary, ensure_ascii=False, indent=2))
    if errors:
        raise SystemExit(1)
    if args.sample:
        rng = random.Random(args.seed)
        sample = []
        for genre, count in (("practical", 35), ("classic", 15)):
            pool = sorted((sid for sid in rows if entries[sid]["genre"] == genre))
            if len(pool) < count:
                raise SystemExit(f"Insufficient {genre} rows for sample")
            sample.extend({**rows[sid], "genre": genre} for sid in rng.sample(pool, count))
        args.sample.parent.mkdir(parents=True, exist_ok=True)
        args.sample.write_text("".join(json.dumps(row, ensure_ascii=False) + "\n" for row in sample), encoding="utf-8", newline="\n")
    if args.apply:
        if missing:
            raise SystemExit(f"Refusing incomplete merge: {len(missing)} missing")
        # Recheck the source immediately before replacing it; batch writers never own this file.
        if PSV.read_text(encoding="utf-8") != original:
            raise SystemExit("Editorial file changed during validation")
        merged = original.rstrip() + "\n" + "".join("|".join(new_rows[sid][key] for key in ("id", "text", "shortMeaning", "authoredOn")) + "\n" for sid in sorted(new_rows))
        PSV.write_text(merged, encoding="utf-8", newline="\n")
        print(f"Merged {len(new_rows)} new meanings; preserved {len(existing)} existing rows")


if __name__ == "__main__":
    main()
