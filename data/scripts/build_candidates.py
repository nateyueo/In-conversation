"""言间 data 侧构建脚本：把已下载的原始数据过质量闸门，产出候选 JSONL 与统计。

只做数据准备，不发布、不写 App 代码、不宣称人工逐条核验。
每条候选带稳定 ID（official:<kind>:<uuid>，注册表持久化复用）、来源/许可字段与
publicationStatus=candidate；缺中文短释义的条目在 publicationBlockers 明示。

用法：python data/scripts/build_candidates.py [--limit-words N]
输出：data/processed/*.jsonl、data/candidate/rejected.*.jsonl、data/metadata/*
"""
from __future__ import annotations

import argparse
import bz2
import gzip
import hashlib
import json
import random
import re
import unicodedata
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

DATA = Path(__file__).resolve().parents[1]
RAW = DATA / "raw"
PROCESSED = DATA / "processed"
METADATA = DATA / "metadata"
CANDIDATE = DATA / "candidate"
EDITORIAL = DATA / "editorial"

DATA_SET_ID = "yanjian-official-content"
SCHEMA_VERSION = 1  # 暂定值，正式 schema 审核中
CONTENT_VERSION = "0.1.0-candidate.1"
RULESET = "yanjian-gate/1"
SNAPSHOT = "2026-10-24"

WORD_LEN_ALLOWED = (2, 4)
SENT_LEN_MIN, SENT_LEN_MAX = 8, 60
CLASSIC_LEN_MIN, CLASSIC_LEN_MAX = 8, 60
JIEBA_FREQ_MIN = 5           # 2 字词：通用语料里出现过的下限（粗糙信号，不是词频权威）
JIEBA_FREQ_MIN_4 = 1         # 4 字词：成语与固定表达在通用语料里天然低频
EDITORIAL_OVERRIDABLE_RULES = {          # 编辑已撰写释义时，这些规则不再一票否决
    "word_no_usage_frequency", "word_def_reference_only",
    "word_def_proper_noun_only", "word_def_noise",
}
RE_REF_DEFS = re.compile(r"^(variant of|old variant|used in|see |surname |abbr\. for|same as|"
                         r"also written|taiwan pr\.|japanese variant|erhua variant|nonstandard|"
                         r"archaic variant|cantonese)")
REJECT_CAP = 20000           # rejected 明细每类最多留多少行，计数永远全量

HAN = r"\u3400-\u4dbf\u4e00-\u9fff"
RE_HAN = re.compile(f"[{HAN}]")
RE_HAN_ONLY = re.compile(f"^[{HAN}]+$")
RE_LATIN = re.compile(r"[A-Za-z]")
RE_URL = re.compile(r"(https?://|www\.|\.com|\.cn|\.net)", re.I)
RE_SENT_END = re.compile(r"[。！？]$")
RE_WEIRD = re.compile(r"[\u3040-\u30ff\uac00-\ud7af\u0400-\u04ff\u0600-\u06ff\u0e00-\u0e7f\ue000-\uf8ff\u3000\ufffd]")
CJK_PUNCT = "，。！？、；：“”‘’（）《》…—·〈〉「」『』"

# 粗粒度不宜内容过滤：仅高频显性词，非安全全覆盖（见 quality_report 的限制说明）
SAFETY_TERMS = [
    "色情", "情色", "性交", "做爱", "强奸", "轮奸", "卖淫", "妓女", "嫖娼", "嫖客",
    "自杀", "自残", "吸毒", "海洛因", "冰毒", "贩毒", "赌博", "赌场",
    "屠杀", "砍死", "杀死", "血腥", "虐杀", "枪杀",
    "傻逼", "傻B", "婊子", "贱人", "操你", "他妈的", "狗娘养",
    "歧视性言论",
]


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def load_index() -> dict:
    p = RAW / "index.json"
    if not p.exists():
        raise SystemExit("raw/index.json 不存在，先运行 fetch.py")
    entries = json.loads(p.read_text(encoding="utf-8"))["entries"]
    return {e["path"]: e for e in entries}


def han_len(s: str) -> int:
    return len(RE_HAN.findall(s))


def norm_key(s: str) -> str:
    """去重键：NFC，去标点/空白，保留汉字与数字。"""
    s = unicodedata.normalize("NFC", s)
    keep = []
    for ch in s:
        if RE_HAN.match(ch) or ch.isdigit():
            keep.append(ch)
    return "".join(keep)


# ---------------------------------------------------------------- 词典 / 频次
def read_cedict(path: Path):
    entries = []
    header = {}
    with gzip.open(path, "rt", encoding="utf-8") as f:
        for line in f:
            if line.startswith("#"):
                if "time=" in line:
                    for tok in line[1:].split():
                        if "=" in tok:
                            k, v = tok.split("=", 1)
                            header[k] = v
                continue
            m = re.match(r"^(\S+) (\S+) \[([^\]]*)\] /(.*)/$", line.rstrip("\n"))
            if not m:
                continue
            trad, simp, pinyin, defs = m.groups()
            entries.append({
                "traditional": trad,
                "simplified": simp,
                "pinyin": pinyin,
                "definitions": [d.strip() for d in defs.split("/") if d.strip()],
                "raw": line.rstrip("\n"),
            })
    return entries, header


def read_jieba(path: Path) -> dict[str, int]:
    freq = {}
    with path.open(encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split(" ")
            if len(parts) < 2:
                continue
            try:
                freq[parts[0]] = int(parts[1])
            except ValueError:
                continue
    return freq


def trad_only_chars(entries) -> set[str]:
    """只在繁体词形出现、从不出现在任何简体词形里的字（用于判断原文繁简，不做转换）。"""
    trad_chars, simp_chars = set(), set()
    for e in entries:
        trad_chars.update(e["traditional"])
        simp_chars.update(e["simplified"])
    return trad_chars - simp_chars


# ---------------------------------------------------------------- 编辑原创释义
def read_glosses(name: str) -> dict[str, dict]:
    """读编辑原创释义文件。分隔符为竖线：词|短释义|分类|标签(; 分隔)|用法说明。

    每条释义由编辑逐条撰写，不是程序按模板拼接；空行与 # 开头行忽略。
    """
    path = EDITORIAL / name
    if not path.exists():
        return {}
    out = {}
    with path.open(encoding="utf-8") as f:
        for ln, line in enumerate(f, 1):
            line = line.rstrip("\n")
            if not line or line.startswith("#"):
                continue
            cols = line.split("|")
            while len(cols) < 5:
                cols.append("")
            text, meaning, category, tags, usage = [c.strip() for c in cols[:5]]
            if not text or not meaning:
                raise SystemExit(f"{path}:{ln} 缺词或释义")
            out[text] = {
                "shortMeaning": meaning,
                "category": category or None,
                "tags": [t.strip() for t in tags.split(";") if t.strip()],
                "usageNote": usage or None,
                "file": f"data/editorial/{name}",
            }
    return out


# ---------------------------------------------------------------- 候选生成
def gate_word(entry, freq: dict, trad_only: set):
    """返回 (ok, rule, reason)。rule 只在 ok=False 时有意义。"""
    text = entry["simplified"]
    if not RE_HAN_ONLY.match(text):
        return False, "word_not_han_only", "含非汉字字符"
    if len(text) not in WORD_LEN_ALLOWED:
        return False, f"word_length_{len(text)}_not_2_or_4", "词长不在 2/4 字"
    defs = entry["definitions"]
    if not defs:
        return False, "word_no_definition", "CC-CEDICT 无英文释义"
    joined = " ".join(defs).lower()
    if all(RE_REF_DEFS.match(d.lower()) for d in defs):
        return False, "word_def_reference_only", "释义仅为变体/参见/姓氏指引"
    if all(re.fullmatch(r"[A-Z][A-Za-z'\-]*( [A-Z][A-Za-z'\-]*)*", d) for d in defs):
        return False, "word_def_proper_noun_only", "释义仅为专名"
    if "noise" in joined and len(defs) == 1:
        return False, "word_def_noise", "释义为噪音标记"
    f = freq.get(text, 0)
    need = JIEBA_FREQ_MIN if len(text) == 2 else JIEBA_FREQ_MIN_4
    if f < need:
        return False, "word_no_usage_frequency", f"通用语料无频次证据（freq={f} < {need}）"
    if any(ch in trad_only for ch in text):
        return False, "word_contains_traditional_only_char", "含仅见于繁体词形的用字"
    return True, None, None


def gate_sentence(text: str, kind: str = "practical"):
    if not text or not text.strip():
        return False, "sentence_empty", "空文本"
    if RE_WEIRD.search(text):
        return False, "sentence_weird_script", "含非常用脚本字符（假名/谚文/私用区/全角空格等）"
    if RE_URL.search(text):
        return False, "sentence_contains_url", "含网址/域名"
    if RE_LATIN.search(text):
        return False, "sentence_contains_latin", "含拉丁字母（常见于外文人名/术语）"
    if len(set(text)) < 5:
        return False, "sentence_too_repetitive", "用字重复过多，像占位文本"
    if "　" in text or "\u3000" in text:
        return False, "sentence_fullwidth_space", "含全角空格"
    for term in SAFETY_TERMS:
        if term in text:
            return False, "sentence_safety_keyword_heuristic", f"命中显性不宜学习词（{term}），非安全全覆盖"
    n = han_len(text)
    lo, hi = (SENT_LEN_MIN, SENT_LEN_MAX) if kind == "practical" else (CLASSIC_LEN_MIN, CLASSIC_LEN_MAX)
    if n < lo:
        return False, "sentence_too_short", f"汉字数 {n} < {lo}"
    if n > hi:
        return False, "sentence_too_long", f"汉字数 {n} > {hi}"
    if not RE_SENT_END.search(text):
        return False, "sentence_not_complete", "结尾无句末标点，疑似片段"
    if kind == "practical" and "……" in text:
        return False, "sentence_ellipsis_placeholder", "含省略号占位"
    return True, None, None


def script_of(text: str, trad_only: set) -> str:
    """判断原文用字：出现任一"仅繁体用字"即记为 traditional，不做繁简转换。"""
    return "traditional" if any(ch in trad_only for ch in text) else "simplified"


def is_ref_defs(defs) -> bool:
    """CC-CEDICT 释义是否只是"某词的变体/参见/姓氏"这类指引，没有实际词义。"""
    return bool(defs) and all(RE_REF_DEFS.match(d.lower()) for d in defs)

def source_block_cedict(entry, lic: dict) -> dict:
    return {
        "id": "cc-cedict",
        "type": "dictionary",
        "title": "CC-CEDICT",
        "quote": entry["raw"],
        "locator": f"{entry['traditional']} {entry['simplified']} [{entry['pinyin']}]",
        "url": lic["homepage"],
        "edition": f"cedict_1_0_ts_utf-8_mdbg.txt.gz @{SNAPSHOT}",
        "licenseName": lic["licenseName"],
        "licenseUrl": lic["licenseUrl"],
        "attribution": "CC-CEDICT (https://cc-cedict.org/wiki/)",
        "evidenceStatus": "located",
        "rightsStatus": "allowed",
    }


def source_block_editorial(gloss: dict) -> dict:
    return {
        "id": "project-editorial",
        "type": "original",
        "title": "言间编辑原创中文短释义",
        "quote": gloss["shortMeaning"],
        "locator": gloss["file"],
        "url": None,
        "edition": None,
        "licenseName": "本项目原创（许可未定稿）",
        "licenseUrl": None,
        "attribution": "言间编辑",
        "evidenceStatus": None,
        "rightsStatus": "project_owned",
    }


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser(description="言间候选数据构建（规则闸门 + 统计）")
    ap.add_argument("--include-traditional", action="store_true",
                    help="把繁体原文也写成单独文件（默认只统计，不做繁简转换）")
    args = ap.parse_args(argv)
    include_traditional = args.include_traditional
    index = load_index()
    src = json.loads((DATA / "sources.json").read_text(encoding="utf-8"))
    lic = {s["id"]: s for s in src["sources"]}

    stats: dict = {"builtAt": now_iso(), "ruleset": RULESET, "stages": {}, "rules": {}, "samples": {}}
    deferred = {"practical_traditional": 0, "classic_traditional": 0}
    inputs = {p: {"sha256": e["sha256"], "bytes": e["bytes"], "url": e["url"]}
              for p, e in index.items() if e["kind"] == "artifact"}

    cedict, cedict_header = read_cedict(RAW / "cc-cedict" / SNAPSHOT / "cedict_1_0_ts_utf-8_mdbg.txt.gz")
    freq = read_jieba(RAW / "jieba-dict" / SNAPSHOT / "dict.txt.big")
    trad_only = trad_only_chars(cedict)

    word_gloss = read_glosses("words.psv")
    idiom_gloss = read_glosses("idioms.psv")
    gloss_all = {**word_gloss, **idiom_gloss}

    # ---- registry
    registry: dict[str, dict] = {}
    for registry_path in (METADATA / "id_registry.jsonl", METADATA / "id_registry.jsonl.gz"):
        if not registry_path.exists():
            continue
        opener = gzip.open if registry_path.suffix == ".gz" else open
        with opener(registry_path, "rt", encoding="utf-8") as f:
            for line in f:
                line = line.strip()
                if line:
                    rec = json.loads(line)
                    registry.setdefault(rec["dedupeKey"], rec)

    def assign_id(kind: str, dedupe_key: str, text: str) -> str:
        rec = registry.get(dedupe_key)
        if rec is None:
            rec = {
                "dedupeKey": dedupe_key,
                "id": f"official:{kind}:{uuid.uuid4()}",
                "kind": kind,
                "createdAt": now_iso(),
            }
            registry[dedupe_key] = rec
        return rec["id"]

    # ---- words
    words_out = []
    rejected_words = []
    rule_word = Counter()
    rule_sent = Counter()
    seen_words = set()
    sense_pick = {}
    for e in cedict:
        sense_pick.setdefault(e["simplified"], []).append(e)

    freq_override = 0
    for text, variants in sense_pick.items():
        # 同一词形可能有多个 CEDICT 条目（含"变体/参见"类指引条目）。先排除纯指引条目，
        # 再取释义最多的一条作代表；义项细分留给编辑，不自动拆成多张卡。
        best = sorted(variants, key=lambda v: (is_ref_defs(v["definitions"]),
                                               -len(v["definitions"]),
                                               variants.index(v)))[0]
        ok, rule, reason = gate_word(best, freq, trad_only)
        if not ok and rule in EDITORIAL_OVERRIDABLE_RULES and text in gloss_all:
            # 编辑已为该词写过释义，等于人工确认这是个在用且有明确词义的词；
            # 频次与释义形态只作排序/提示信号，不再一票否决。
            ok, rule, reason = True, None, None
            freq_override += 1
        stats["stages"]["cedict_wordforms"] = stats["stages"].get("cedict_wordforms", 0) + 1
        if not ok:
            rule_word[rule] += 1
            if len(rejected_words) < REJECT_CAP:
                rejected_words.append({"kind": "word", "ruleId": rule, "reason": reason,
                                       "text": text, "pinyin": best["pinyin"],
                                       "sourceId": "cc-cedict"})
            continue
        key = f"word|{norm_key(text)}"
        if key in seen_words:
            rule_word["word_duplicate_text"] += 1
            continue
        seen_words.add(key)

        gloss = gloss_all.get(text)
        blockers = []
        if not gloss:
            blockers.append("missing_short_meaning")
        else:
            blockers.append("pending_editorial_review")
        is_idiom = text in idiom_gloss
        entry = {
            "id": assign_id("word", key, text),
            "recordVersion": 1,
            "kind": "word",
            "text": text,
            "pinyin": best["pinyin"],
            "traditionalForm": best["traditional"] if best["traditional"] != text else None,
            "definitionEn": best["definitions"],
            "shortMeaning": gloss["shortMeaning"] if gloss else None,
            "charLength": len(text),
            "genre": "classic" if is_idiom else "practical",
            "category": gloss["category"] if gloss else None,
            "tags": gloss["tags"] if gloss else [],
            "usageNote": gloss["usageNote"] if gloss else None,
            "publicationStatus": "candidate",
            "publicationBlockers": blockers,
            "evidenceStatus": "located",
            "frequencySignal": freq.get(text, 0),
            "sources": [source_block_cedict(best, lic["cc-cedict"])]
                       + ([source_block_editorial(gloss)] if gloss else []),
        }
        words_out.append(entry)

    # ---- sentences: Tatoeba cmn
    cc0_ids = set()
    cc0_path = RAW / "tatoeba" / SNAPSHOT / "cmn_sentences_CC0.tsv.bz2"
    if cc0_path.exists():
        with bz2.open(cc0_path, "rt", encoding="utf-8") as f:
            for line in f:
                parts = line.rstrip("\n").split("\t")
                if parts and parts[0].isdigit():
                    cc0_ids.add(parts[0])

    def practical_entry(key: str, text: str, parts: list[str], script: str) -> dict:
        sid, owner = parts[0], parts[3]
        is_cc0 = sid in cc0_ids
        blockers = ["missing_short_meaning", "pending_expression_purpose_tagging"]
        if script == "traditional":
            blockers.append("script_not_simplified")
        return {
            "id": assign_id("sentence", key, text),
            "recordVersion": 1,
            "kind": "sentence",
            "text": text,
            "charLength": han_len(text),
            "script": script,
            "shortMeaning": None,
            "genre": "practical",
            "category": None,
            "tags": [],
            "usageNote": None,
            "publicationStatus": "candidate",
            "publicationBlockers": blockers,
            "evidenceStatus": "located",
            "sourceRef": {"tatoebaSentenceId": int(sid), "owner": owner, "cc0": is_cc0,
                          "addedAt": parts[4] if len(parts) > 4 else None,
                          "lastModified": parts[5] if len(parts) > 5 else None},
            "sources": [{
                "id": "tatoeba",
                "type": "community-corpus",
                "title": "Tatoeba 普通话例句",
                "quote": text,
                "locator": f"Tatoeba sentence #{sid}",
                "url": f"https://tatoeba.org/en/sentences/show/{sid}",
                "edition": f"cmn_sentences_detailed.tsv.bz2 @{SNAPSHOT}",
                "licenseName": "CC0 1.0" if is_cc0 else "CC BY 2.0 FR",
                "licenseUrl": ("https://creativecommons.org/publicdomain/zero/1.0/"
                               if is_cc0 else "https://creativecommons.org/licenses/by/2.0/fr/"),
                "attribution": (f"Tatoeba contributor: {owner}"
                                if owner not in ("", "\\N") else "Tatoeba contributor: 未署名"),
                "evidenceStatus": "located",
                "rightsStatus": "allowed",
            }],
        }

    sentences, sentences_trad = [], []
    rejected_sent = []
    seen_sent = set()
    tat_total = 0
    with bz2.open(RAW / "tatoeba" / SNAPSHOT / "cmn_sentences_detailed.tsv.bz2", "rt", encoding="utf-8") as f:
        for line in f:
            parts = line.rstrip("\n").split("\t")
            if len(parts) < 4:
                continue
            sid, text = parts[0], parts[2]
            tat_total += 1
            if not sid.isdigit():
                continue
            ok, rule, reason = gate_sentence(text, "practical")
            if not ok:
                rule_sent[rule] += 1
                if len(rejected_sent) < REJECT_CAP:
                    rejected_sent.append({"kind": "sentence", "ruleId": rule, "reason": reason,
                                          "text": text, "sourceId": "tatoeba", "sourceItemId": sid})
                continue
            key = f"sentence|{norm_key(text)}"
            if key in seen_sent:
                rule_sent["sentence_duplicate"] += 1
                continue
            seen_sent.add(key)
            script = script_of(text, trad_only)
            if script == "traditional":
                # 不做繁简转换：默认不入库，只统计；加 --include-traditional 才落到单独文件
                deferred["practical_traditional"] += 1
                if include_traditional:
                    sentences_trad.append(practical_entry(key, text, parts, script))
                continue
            sentences.append(practical_entry(key, text, parts, script))

    # ---- sentences: 唐诗（公有领域原文）
    poetry, poetry_trad = [], []
    rule_classic = Counter()
    seen_classic = set()

    def classic_entry(key: str, seg: str, poem: dict, author: str, title: str,
                      pf: Path, script: str) -> dict:
        blockers = ["missing_short_meaning", "pending_editorial_review"]
        if script == "traditional":
            blockers.append("script_not_simplified")
        return {
            "id": assign_id("sentence", key, seg),
            "recordVersion": 1,
            "kind": "sentence",
            "text": seg,
            "charLength": han_len(seg),
            "script": script,
            "shortMeaning": None,
            "genre": "classic",
            "category": None,
            "tags": [],
            "usageNote": None,
            "publicationStatus": "candidate",
            "publicationBlockers": blockers,
            "evidenceStatus": "located",
            "sourceRef": {"poemId": poem.get("id"), "author": author, "workTitle": title,
                          "rawFile": f"raw/chinese-poetry/{SNAPSHOT}/{pf.name}"},
            "sources": [{
                "id": "chinese-poetry",
                "type": "public-domain-classical",
                "title": f"《{title}》",
                "quote": seg,
                "locator": f"《{title}》/{author}（唐）",
                "url": f"https://github.com/chinese-poetry/chinese-poetry/blob/master/%E5%85%A8%E5%94%90%E8%AF%97/{pf.name}",
                "edition": f"chinese-poetry master @{SNAPSHOT}（{pf.name}）",
                "licenseName": "MIT（仓库整理层）/ 原文公有领域",
                "licenseUrl": "https://github.com/chinese-poetry/chinese-poetry/blob/master/LICENSE",
                "attribution": f"{author}《{title}》（唐代，公有领域）",
                "evidenceStatus": "located",
                "rightsStatus": "allowed",
            }],
        }

    poem_files = sorted((RAW / "chinese-poetry" / SNAPSHOT).glob("poet.tang.*.json"))
    for pf in poem_files:
        for poem in json.loads(pf.read_text(encoding="utf-8")):
            title = (poem.get("title") or "").strip()
            author = (poem.get("author") or "").strip()
            if not title or not author:
                rule_classic["classic_missing_meta"] += 1
                continue
            for para in poem.get("paragraphs") or []:
                # 不剪断原文：只按句末标点切分，段内多句仍按整句保留
                segs = re.findall(rf"[^{HAN}]*?([{HAN}][{HAN}{re.escape(CJK_PUNCT)}]*[。！？])", para)
                if not segs:
                    continue
                for seg in segs:
                    seg = seg.strip()
                    ok, rule, reason = gate_sentence(seg, "classic")
                    if not ok:
                        rule_classic[rule] += 1
                        continue
                    key = f"sentence|{norm_key(seg)}"
                    if key in seen_classic:
                        rule_classic["classic_duplicate"] += 1
                        continue
                    seen_classic.add(key)
                    script = script_of(seg, trad_only)
                    if script == "traditional":
                        # 全唐诗语料多为繁体：保留原文不转换，默认不入库，只在统计里计数
                        deferred["classic_traditional"] += 1
                        if include_traditional:
                            poetry_trad.append(classic_entry(key, seg, poem, author, title, pf, script))
                        continue
                    poetry.append(classic_entry(key, seg, poem, author, title, pf, script))

    # ---- 写文件
    PROCESSED.mkdir(parents=True, exist_ok=True)
    CANDIDATE.mkdir(parents=True, exist_ok=True)
    METADATA.mkdir(parents=True, exist_ok=True)

    def dump_jsonl(path: Path, rows):
        with path.open("w", encoding="utf-8", newline="\n") as f:
            for r in rows:
                f.write(json.dumps(r, ensure_ascii=False, sort_keys=False) + "\n")

    words_out.sort(key=lambda r: (-r["frequencySignal"], r["text"]))
    dump_jsonl(PROCESSED / "entries.words.candidate.jsonl", words_out)
    dump_jsonl(PROCESSED / "entries.sentences.practical.candidate.jsonl", sentences)
    dump_jsonl(PROCESSED / "entries.sentences.classic.candidate.jsonl", poetry)
    if include_traditional:
        dump_jsonl(PROCESSED / "entries.sentences.practical.traditional.candidate.jsonl", sentences_trad)
        dump_jsonl(PROCESSED / "entries.sentences.classic.traditional.candidate.jsonl", poetry_trad)
    dump_jsonl(CANDIDATE / "rejected.words.jsonl", rejected_words)
    dump_jsonl(CANDIDATE / "rejected.sentences.jsonl", rejected_sent)
    reg_rows = [{"dedupeKey": r["dedupeKey"], "id": r["id"], "kind": r["kind"],
                 "createdAt": r["createdAt"]} for r in sorted(registry.values(),
                                                              key=lambda r: r["dedupeKey"])]
    dump_jsonl(METADATA / "id_registry.jsonl", reg_rows)
    with gzip.open(METADATA / "id_registry.jsonl.gz", "wt", encoding="utf-8", newline="\n") as f:
        for r in reg_rows:
            f.write(json.dumps(r, ensure_ascii=False) + "\n")

    # ---- 署名清单（离线署名页用）
    owners = Counter()
    for s in sentences:
        owners[(s["sourceRef"]["owner"], s["sources"][0]["licenseName"])] += 1
    with (PROCESSED / "attribution.owners.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for (owner, license_name), n in owners.most_common():
            f.write(json.dumps({"owner": owner, "licenseName": license_name, "sentenceCount": n},
                               ensure_ascii=False) + "\n")
    # 来源级署名（离线署名页按来源逐条列出，含许可与是否需要相同方式共享）
    used_sources = ["cc-cedict", "tatoeba", "chinese-poetry"]
    with (PROCESSED / "attribution.sources.jsonl").open("w", encoding="utf-8", newline="\n") as f:
        for sid in used_sources:
            s = lic[sid]
            f.write(json.dumps({
                "sourceId": sid,
                "title": s["title"],
                "licenseName": s["licenseName"],
                "licenseUrl": s["licenseUrl"],
                "attributionText": s["attributionText"],
                "shareAlikeRequired": s["shareAlikeRequired"],
                "codeLicenseName": s["codeLicenseName"],
                "codeLicenseUrl": s["codeLicenseUrl"],
                "dataLicenseVsCodeLicense": "数据许可见 licenseName；代码/整理层许可见 codeLicenseName，两者不混用",
            }, ensure_ascii=False) + "\n")

    # ---- 统计与报告
    glossed = [w for w in words_out if w["shortMeaning"]]
    stats["stages"].update({
        "cedict_entries_total": len(cedict),
        "cedict_word_entries_2char": sum(1 for e in cedict if len(e["simplified"]) == 2),
        "cedict_word_entries_4char": sum(1 for e in cedict if len(e["simplified"]) == 4),
        "words_candidates": len(words_out),
        "words_glossed": len(glossed),
        "words_candidates_2char": sum(1 for w in words_out if w["charLength"] == 2),
        "words_candidates_4char": sum(1 for w in words_out if w["charLength"] == 4),
        "words_glossed_2char": sum(1 for w in glossed if w["charLength"] == 2),
        "words_glossed_4char": sum(1 for w in glossed if w["charLength"] == 4),
        "tatoeba_rows": tat_total,
        "tatoeba_cc0_ids": len(cc0_ids),
        "sentences_practical": len(sentences),
        "sentences_practical_traditional_deferred": deferred["practical_traditional"],
        "sentences_classic": len(poetry),
        "poem_files": len(poem_files),
        "sentences_classic_traditional_deferred": deferred["classic_traditional"],
        "words_frequency_override_by_editorial": freq_override,
        "sentences_total": len(sentences) + len(poetry),
    })
    stats["rules"]["words"] = dict(rule_word.most_common())
    stats["rules"]["sentences_practical"] = dict(rule_sent.most_common())
    stats["rules"]["sentences_classic"] = dict(rule_classic.most_common())
    stats["editorial"] = {
        "words_tsv_entries": len(word_gloss),
        "idioms_tsv_entries": len(idiom_gloss),
        "glossed_matched_to_cedict": len([t for t in gloss_all if t in sense_pick]),
        "glossed_not_in_cedict": sorted([t for t in gloss_all if t not in sense_pick]),
        "glossed_present_but_rejected": sorted([t for t in gloss_all
                                                if t in sense_pick and t not in {w["text"] for w in words_out}]),
    }
    stats["inputs"] = inputs
    stats["cedict_header"] = cedict_header
    stats["distributions"] = {
        "words_by_genre": dict(Counter(w["genre"] for w in words_out)),
        "words_by_category": dict(Counter(w["category"] or "null" for w in words_out).most_common()),
        "sentences_by_genre": dict(Counter([s["genre"] for s in sentences] + [s["genre"] for s in poetry])),
        "sentence_len_practical": dict(Counter(s["charLength"] for s in sentences).most_common(10)),
        "sentence_len_classic": dict(Counter(s["charLength"] for s in poetry).most_common(10)),
        "top_tatoeba_owners": {f"{o} / {l}": n for (o, l), n in owners.most_common(10)},
    }
    (METADATA / "source_stats.json").write_text(
        json.dumps(stats, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 10 条混合样本（给主代理抽看，不替代人工核验）
    random.seed(20261024)
    sample = (random.sample(glossed, min(4, len(glossed)))
              + random.sample([w for w in words_out if not w["shortMeaning"]], min(2, len(words_out)))
              + random.sample(sentences, min(2, len(sentences)))
              + random.sample(poetry, min(2, len(poetry))))
    dump_jsonl(METADATA / "sample-10.jsonl", sample)

    manifest = {
        "format": f"{DATA_SET_ID}/schemaVersion{SCHEMA_VERSION}",
        "schemaVersion": SCHEMA_VERSION,
        "schemaVersionStatus": "provisional（正式 schema 正在网页审核，本文件不代表已发布包）",
        "datasetId": DATA_SET_ID,
        "contentVersion": CONTENT_VERSION,
        "contentVersionStatus": "candidate: 未经发布审批，不得当作发布版本号",
        "builtAt": now_iso(),
        "ruleset": RULESET,
        "inputs": inputs,
        "counts": {k: stats["stages"][k] for k in
                   ("words_candidates", "words_glossed", "sentences_practical",
                    "sentences_classic", "sentences_total")},
        "outputs": {
            "processed/entries.words.candidate.jsonl": len(words_out),
            "processed/entries.sentences.practical.candidate.jsonl": len(sentences),
            "processed/entries.sentences.classic.candidate.jsonl": len(poetry),
            "processed/attribution.owners.jsonl": len(owners),
            "candidate/rejected.words.jsonl": len(rejected_words),
            "candidate/rejected.sentences.jsonl": len(rejected_sent),
        },
        "publicationStatus": "candidate（全部条目）",
        "verificationStatement": "条目仅经规则闸门与自动统计；未做人工逐条核验，evidenceStatus=located 仅表示原文已在上述快照中定位。",
    }
    (METADATA / "build_manifest.json").write_text(
        json.dumps(manifest, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    write_report(stats, manifest, words_out, glossed, sentences, poetry)
    print(json.dumps(manifest["counts"], ensure_ascii=False))
    print("words_by_genre", stats["distributions"]["words_by_genre"])
    print("glossed_not_in_cedict", len(stats["editorial"]["glossed_not_in_cedict"]))
    return 0


def write_report(stats, manifest, words_out, glossed, sentences, poetry) -> None:
    s = stats["stages"]
    rules_w = stats["rules"]["words"]
    rules_p = stats["rules"]["sentences_practical"]
    rules_c = stats["rules"]["sentences_classic"]

    def table(d):
        if not d:
            return "（无）"
        return "\n".join(f"| `{k}` | {v} |" for k, v in d.items())

    md = f"""# 数据准备报告（自动生成，未人工逐条核验）

生成时间：{stats['builtAt']}　规则版本：{RULESET}　内容版本：`{manifest['contentVersion']}`
本轮只产出候选（`publicationStatus=candidate`），不构成发布包，也不代表正式 schema 已定稿。

## 1. 输入快照与哈希（传输完整性，非内容签名）

| 文件 | 字节 | sha256（前 16） |
| --- | --- | --- |
""" + "\n".join(
        f"| `{p}` | {v['bytes']} | `{v['sha256'][:16]}` |" for p, v in manifest["inputs"].items()
    ) + f"""

## 2. 各段通过量

| 阶段 | 数量 |
| --- | --- |
""" + table({k: s[k] for k in s if isinstance(s[k], int)}) + f"""

## 3. 词条闸门（按规则计数，全量）

| 规则 | 拒绝数 |
| --- | --- |
""" + table(rules_w) + f"""

## 4. 实用句闸门（Tatoeba cmn）

| 规则 | 拒绝数 |
| --- | --- |
""" + table(rules_p) + f"""

## 5. 古典句闸门（全唐诗，公有领域原文）

| 规则 | 拒绝数 |
| --- | --- |
""" + table(rules_c) + f"""

## 6. 编辑原创释义

- 原创词条释义文件：`data/editorial/words.psv`（{stats['editorial']['words_tsv_entries']} 条）、`data/editorial/idioms.psv`（{stats['editorial']['idioms_tsv_entries']} 条）
- 与 CC-CEDICT 词形匹配：{stats['editorial']['glossed_matched_to_cedict']} 条
- 未在 CC-CEDICT 找到（本轮未采用）：{len(stats['editorial']['glossed_not_in_cedict'])} 条
- 匹配到词形但未过闸门：{len(stats['editorial']['glossed_present_but_rejected'])} 条
- 释义为人工撰写的一句现代汉语，不是程序模板拼接；条数即实际撰写条数。

## 7. 许可与署名

- CC-CEDICT：CC BY-SA 4.0（数据许可），仅使用词形/拼音/英文释义字段；中文短释义为原创，仍需在上线时保留 CC-CEDICT 署名与许可链接，并注明修改。
- Tatoeba：逐句许可，{s['tatoeba_cc0_ids']} 条 CC0 候选集中实际可用句以 `sourceRef.cc0` 标记，其余按 CC BY 2.0 FR 署名到具体 contributor。
- chinese-poetry：仓库整理层 MIT（代码/整理许可），唐代诗作原文属公有领域；未使用任何现代译文、注释。
- jieba 词典：仅作词频信号（`selection_signal_only`），其文本不进入候选或发布包。
- chinese-xinhua：不入库（仓库 MIT 不能证明逐条释义/例句的再发布权）。
- 代码许可与数据许可分开登记，见 `data/sources.json` 的 `codeLicense*` 与 `license*` 字段。

## 8. 已知限制（不能当作安全或质量全覆盖）

1. 规则闸门只覆盖结构、长度、繁简用字、重复、网址/拉丁字母、显性不宜词（{len(SAFETY_TERMS)} 条关键词）与脚本字符；**不是内容安全全覆盖**，语义得当、真实性、翻译腔仍需人工/模型二次筛选。
2. 未做繁简转换：疑似繁体句不转换后当原文，默认不写盘，只在统计里计数（`*_traditional_deferred`）。繁简判定是启发式（CC-CEDICT 繁/简用字差集），个别两体同形的字（如「徵」）会漏判，正式发布前需权威繁简判定。
3. 实用句按 `norm_key` 去重（去标点/空白），近义句只做精确去重，未聚类。
4. 词条闸门对同形多义只保留一个代表条目（先排除"变体/参见"类条目，再取释义最多者），义项细分待编辑决策。
5. `evidenceStatus=located` 仅表示该原文能在登记的下载快照中定位，不等于人工逐条核验，也不等于发布审批通过。
6. 缺中文短释义的词条与全部句子仍是"候选"，不满足成品要求。
7. Tatoeba 语料含翻译腔与人名句（汤姆、玛丽等），闸门不判断自然度；`sentence_too_short` 已过滤 8 字以下短句，但"自然、值得学"仍需编辑或模型二次筛选。
8. 古典句取自全唐诗 8 个分卷（约 8,000 首），含较多郊庙歌辞等冷僻篇目；是否适合入门需编辑挑选，未经挑选不宜直接发布。
9. 词条候选池按"CC-CEDICT 词形 + 通用语料频次"筛出，仍含低价值专名与生僻术语（如"颖果""肛裂""北韩"）；这些词没有中文释义，标 `missing_short_meaning`，不是成品。
"""
    (METADATA / "quality_report.md").write_text(md, encoding="utf-8")


if __name__ == "__main__":
    raise SystemExit(main())