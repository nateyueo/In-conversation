"""言间正式内容包构建（data/processed/official-content.json）。

按固定契约输出单文件 JSON：
  {format:'yanjian-official-content', schemaVersion:1, datasetId:'yanjian-text',
   contentVersion:1, entries:[...]}

来源：data/processed/ 候选 + data/editorial/ 原创释义 + data/raw/ 原始快照。
只做数据打包与二次筛选，不写 App 代码；发布资格 = 来源与许可已登记、结构完整、
去重、过滤后抽样无系统性明显问题。evidenceStatus='source_checked' 只表示
原文与公开来源快照匹配，不等于人工逐条核验。

用法：python data/scripts/build_release.py [--sample N]
"""
from __future__ import annotations

import argparse
import gzip
import json
import re
import uuid
from collections import Counter, defaultdict
from datetime import datetime, timezone
from pathlib import Path

import sys
sys.path.insert(0, str(Path(__file__).resolve().parent))
from build_candidates import read_cedict, trad_only_chars  # noqa: E402

DATA = Path(__file__).resolve().parents[1]
PROCESSED = DATA / "processed"
METADATA = DATA / "metadata"
EDITORIAL = DATA / "editorial"
CORRECTIONS = DATA / "corrections" / "text-corrections.psv"
SENTENCE_GLOSS = EDITORIAL / "sentences.psv"
RAW = DATA / "raw"
SNAPSHOT = "2026-10-24"

FORMAT = "yanjian-official-content"
SCHEMA_VERSION = 1
DATASET_ID = "yanjian-text"
CONTENT_VERSION = 5   # v5：古典白话修订；保持稳定 ID，登记已发布释义的修订号

OUT_PATH = PROCESSED / "official-content.json"

HAN = r"\u3400-\u4dbf\u4e00-\u9fff"
RE_HAN = re.compile(f"[{HAN}]")
RE_HAN_ONLY = re.compile(f"^[{HAN}]+$")
RE_LATIN = re.compile(r"[A-Za-z]")
RE_WEIRD = re.compile(r"[\u3040-\u30ff\uac00-\ud7af\u0400-\u0e7f\ue000-\uf8ff\u3000\ufffd]")
RE_SENT_END = re.compile(r"[。！？]$")
CJK_PUNCT = "，。！？、；：“”‘’（）《》…—·〈〉「」『』"

WORD_LEN = (2, 4)
SENT_LEN = (8, 60)
GLOSS_LEN = (6, 60)
SENTENCE_GLOSS_LEN = (6, 60)

# ---- 二次筛选词表（保守、可复核，不是内容安全全覆盖）
NAME_TERMS = [
    "汤姆", "汤米", "玛丽", "玛莉", "杰克", "约翰", "麦克", "彼得", "苏珊", "露西",
    "丽莎", "大卫", "詹姆斯", "史蒂夫", "鲍勃", "琳达", "凯特", "保罗", "安妮",
    "艾米", "爱丽丝", "艾丽斯", "哈罗德", "查尔斯", "肯尼", "南希", "玛丽亚",
]
META_TERMS = ["tatoeba", "用中文怎么说", "用英语怎么说", "中文怎么说", "英语怎么说",
              "这句话怎么", "这个词怎么", "怎么说来着"]
PLACEHOLDER_TERMS = ["某人", "某某", "××", "XX"]
VULGAR_TERMS = ["呵呵", "哈哈", "嘿嘿", "哎哟", "哎呦"]
INAPPROPRIATE_TERMS = ["死", "杀", "枪", "毒", "揍", "打架", "暴力", "裸", "屁股",
                       "强奸", "妓", "赌", "自杀", "吸毒", "色情", "性交", "做爱"]
LOOSE_STOP = set("了的些一很就都也还个这那是有在")

# ---- 领域分类关键词（只在单一类别命中时采用，否则归"日常交流"）
PRACTICAL_CATEGORIES = [
    ("问候与礼貌", ["你好", "您好", "早上好", "晚安", "再见", "谢谢", "不客气", "对不起",
                    "抱歉", "不好意思", "欢迎", "辛苦", "请问", "麻烦您"]),
    ("请求与建议", ["帮我", "帮忙", "能不能", "可以吗", "要不要", "建议", "应该",
                    "请帮", "请你", "让我"]),
    ("情感与心情", ["高兴", "开心", "快乐", "难过", "伤心", "担心", "害怕", "生气", "喜欢",
                    "讨厌", "失望", "感动", "紧张", "着急", "放心", "心疼", "盼望"]),
    ("家庭与朋友", ["爸爸", "妈妈", "父母", "哥哥", "姐姐", "弟弟", "妹妹", "孩子", "儿子",
                    "女儿", "家人", "朋友", "同学", "同事", "爷爷", "奶奶", "妻子", "丈夫",
                    "男朋友", "女朋友"]),
    ("学习与工作", ["学校", "老师", "学生", "考试", "作业", "学习", "上课", "公司", "上班",
                    "工作", "会议", "老板", "毕业", "论文", "面试", "项目", "实习"]),
    ("饮食与生活", ["吃饭", "做饭", "咖啡", "餐厅", "早餐", "午饭", "晚饭", "水果", "面包",
                    "厨房", "好吃", "牛奶", "蛋糕", "喝茶", "筷子"]),
    ("出行与地点", ["车站", "机场", "火车", "飞机", "地铁", "公交", "旅行", "宾馆", "酒店",
                    "出门", "路上", "回家", "出发", "假期", "旅游", "散步"]),
    ("购物与金钱", ["购物", "商店", "超市", "付款", "价格", "便宜", "打折", "礼物", "退货"]),
    ("天气与环境", ["天气", "下雨", "刮风", "太阳", "空气", "季节", "下雪", "阴天", "晴天"]),
    ("健康与身体", ["医生", "医院", "头疼", "感冒", "身体", "睡觉", "休息", "生病", "发烧",
                    "健康", "锻炼"]),
    ("时间与安排", ["明天", "今天", "昨天", "时间", "星期", "以后", "以前", "刚才", "下周",
                    "去年", "小时", "分钟"]),
]
CLASSIC_CATEGORIES = [
    ("写景状物", ["明月", "春风", "秋风", "青山", "白云", "江山", "流水", "落日", "花开",
                  "江湖", "江南", "山川", "风霜", "烟波", "杨柳", "桃花"]),
    ("送别思乡", ["故乡", "离别", "相思", "归去", "归来", "他乡", "故人", "送君", "思乡"]),
    ("抒情言志", ["惆怅", "寂寞", "伤春", "悲秋", "憔悴", "断肠", "多情", "无情", "欢娱"]),
    ("咏史怀古", ["古人", "昔日", "汉代", "秦时", "君王", "宫殿", "六朝", "汉家", "当年"]),
    ("边塞征战", ["将军", "沙场", "战马", "边关", "烽火", "征人", "胡马", "塞外", "弓刀"]),
    ("人生感怀", ["人生", "人间", "世事", "百年", "老去", "浮生", "万事", "白发", "青春",
                  "少年"]),
]
REJECT_RULES: Counter = Counter()


def now_iso() -> str:
    return datetime.now(timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ")


def han_len(s: str) -> int:
    return len(RE_HAN.findall(s))


def loose_key(s: str) -> str:
    return "".join(ch for ch in s if ch not in LOOSE_STOP and (RE_HAN.match(ch) or ch.isdigit()))


def norm_key(s: str) -> str:
    return "".join(ch for ch in s if RE_HAN.match(ch) or ch.isdigit())


# ---------------------------------------------------------------- ID 注册表
class Registry:
    """稳定 ID 注册表：dedupeKey -> official:<kind>:<uuid4>，重跑复用，不重新分配。

    同时支持压缩登记表（.jsonl.gz）；写入时两种形式都更新，.jsonl 可被 .gitignore 忽略。
    """

    def __init__(self) -> None:
        self.map: dict[str, dict] = {}
        self.plain = METADATA / "id_registry.jsonl"
        self.gz = METADATA / "id_registry.jsonl.gz"
        for path in (self.plain, self.gz):
            if path.exists():
                opener = gzip.open if path.suffix == ".gz" else open
                with opener(path, "rt", encoding="utf-8") as f:
                    for line in f:
                        line = line.strip()
                        if not line:
                            continue
                        rec = json.loads(line)
                        self.map.setdefault(rec["dedupeKey"], rec)
        self.new_count = 0

    def get(self, kind: str, dedupe_key: str) -> str:
        rec = self.map.get(dedupe_key)
        if rec is None:
            rec = {
                "dedupeKey": dedupe_key,
                "id": f"official:{kind}:{uuid.uuid4()}",
                "kind": kind,
                "createdAt": now_iso(),
            }
            self.map[dedupe_key] = rec
            self.new_count += 1
        return rec["id"]

    def save(self) -> None:
        rows = [self.map[k] for k in sorted(self.map)]
        body = "".join(json.dumps(r, ensure_ascii=False) + "\n" for r in rows)
        self.plain.write_text(body, encoding="utf-8", newline="\n")
        with gzip.open(self.gz, "wt", encoding="utf-8", newline="\n") as f:
            f.write(body)


# ---------------------------------------------------------------- 编辑释义
def load_editorial() -> dict[str, dict]:
    out = {}
    for name in ("words.psv", "idioms.psv"):
        path = EDITORIAL / name
        for ln, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            cols = (line.split("|") + ["", "", "", ""])[:5]
            text, meaning, category, tags, usage = [c.strip() for c in cols]
            if not text or not meaning:
                raise SystemExit(f"{path}:{ln} 缺词或释义")
            out[text] = {
                "shortMeaning": meaning,
                "category": category,
                "tags": [t.strip() for t in tags.split(";") if t.strip()],
                "usageNote": usage,
                "file": f"data/editorial/{name}",
            }
    return out


# ---------------------------------------------------------------- 来源块
def src_cedict(locator: str) -> dict:
    return {
        "id": "cc-cedict",
        "type": "dictionary",
        "title": "CC-CEDICT",
        "quote": None,
        "locator": locator,
        "url": "https://cc-cedict.org/wiki/",
        "edition": "cedict_1_0_ts_utf-8_mdbg.txt.gz @2026-10-24",
        "licenseName": "CC BY-SA 4.0",
        "licenseUrl": "https://creativecommons.org/licenses/by-sa/4.0/",
        "attribution": "CC-CEDICT（https://cc-cedict.org/wiki/），CC BY-SA 4.0；本项目仅使用其词形与拼音，中文释义为本项目原创，未采用其英文释义。",
        "evidenceStatus": "source_checked",
        "rightsStatus": "open_license",
    }


def src_original(locator: str) -> dict:
    return {
        "id": "yanjian-original",
        "type": "original",
        "title": "言间原创中文短释义",
        "quote": None,
        "locator": locator,
        "url": None,
        "edition": None,
        "licenseName": "言间自制内容（本项目原创，随应用分发）",
        "licenseUrl": None,
        "attribution": "言间编辑原创释义；非第三方词典或翻译，不沿用任何外部释义文本。",
        "evidenceStatus": "source_checked",
        "rightsStatus": "original",
    }


def src_tatoeba(sid: str, owner: str, is_cc0: bool) -> dict:
    return {
        "id": f"tatoeba:{sid}",
        "type": "community_sentence",
        "title": None,
        "quote": None,
        "locator": f"Tatoeba sentence #{sid}",
        "url": f"https://tatoeba.org/en/sentences/show/{sid}",
        "edition": "cmn_sentences_detailed.tsv.bz2 @2026-10-24",
        "licenseName": "CC0 1.0" if is_cc0 else "CC BY 2.0 FR",
        "licenseUrl": ("https://creativecommons.org/publicdomain/zero/1.0/" if is_cc0
                       else "https://creativecommons.org/licenses/by/2.0/fr/"),
        "attribution": (f"Tatoeba 贡献者：{owner}" if owner else "Tatoeba 未署名贡献者"),
        "evidenceStatus": "source_checked",
        "rightsStatus": "open_license",
    }


def src_classic(pid: str, title: str, author: str, url: str, edition_name: str,
                dynasty: str) -> dict:
    return {
        "id": f"chinese-poetry:{pid}",
        "type": "public_domain_text",
        "title": f"《{title}》",
        "quote": None,
        "locator": author,
        "url": url,
        "edition": f"chinese-poetry @2026-10-24（{edition_name}）",
        "licenseName": f"公有领域原文（{dynasty}作品）；仓库整理层 MIT",
        "licenseUrl": "https://github.com/chinese-poetry/chinese-poetry/blob/master/LICENSE",
        "attribution": f"{author}《{title}》，{dynasty}作品，原文属公有领域；文本整理取自 chinese-poetry 仓库。未使用任何现代译文或注释。",
        "evidenceStatus": "source_checked",
        "rightsStatus": "public_domain",
    }


def src_sentence_gloss(locator: str) -> dict:
    """句意短释义的原创来源块：type=original、rightsStatus=original，申明非第三方译文。"""
    return {
        "id": "yanjian-original-sentence-gloss",
        "type": "original",
        "title": "言间原创中文句意短释义",
        "quote": None,
        "locator": locator,
        "url": None,
        "edition": None,
        "licenseName": "言间自制内容（本项目原创，随应用分发）",
        "licenseUrl": None,
        "attribution": "本句短释义为言间编辑原创：只按原文字面重述句意，未采用任何第三方译文、注本或词典释义。",
        "evidenceStatus": "source_checked",
        "rightsStatus": "original",
    }



# ---------------------------------------------------------------- 正文校改
def load_corrections() -> list[dict]:
    """读取人工确认过的单条校改（stableId|sourceId|old|new|recordVersion|证据|说明|日期）。

    只修已确认的具体字词错误；不做通假/异体字批量替换，不改 raw。
    """
    if not CORRECTIONS.exists():
        return []
    rows = []
    for ln, line in enumerate(CORRECTIONS.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = line.split("|")
        if len(cols) != 8:
            raise SystemExit(f"{CORRECTIONS}:{ln} 列数应为 8，实际 {len(cols)}")
        stable_id, source_id, old_text, new_text, rec_ver, evidence, note, when = [c.strip() for c in cols]
        rows.append({"stableId": stable_id, "sourceId": source_id, "oldText": old_text,
                     "newText": new_text, "recordVersion": int(rec_ver),
                     "verifiedAgainst": evidence, "note": note, "correctedAt": when})
    return rows


def apply_corrections(entries: list[dict], corrections: list[dict]) -> list[dict]:
    """按稳定 ID 定点校改：正文改新、recordVersion 升到表内值、ID 不变。"""
    by_id = {e["id"]: e for e in entries}
    applied = []
    for c in corrections:
        e = by_id.get(c["stableId"])
        if e is None:
            raise SystemExit(f"校改目标 id 不在包内：{c['stableId']}")
        if c["sourceId"] and e["sources"][0]["id"] != c["sourceId"]:
            raise SystemExit(f"校改来源不匹配：{e['sources'][0]['id']} != {c['sourceId']}")
        if e["text"] == c["newText"]:
            pass                                    # 已应用，重跑幂等
        elif e["text"] == c["oldText"]:
            e["text"] = c["newText"]
        else:
            raise SystemExit(f"校改前的正文与表内 oldText 不符：{e['text']}")
        e["recordVersion"] = max(e["recordVersion"], c["recordVersion"])
        applied.append({"id": c["stableId"], "oldText": c["oldText"], "newText": c["newText"],
                        "recordVersion": e["recordVersion"], "verifiedAgainst": c["verifiedAgainst"],
                        "correctedAt": c["correctedAt"]})
    return applied


# ---------------------------------------------------------------- 句子原创短释义
def load_sentence_meanings() -> dict[str, dict]:
    """读取编辑原创句意短释义（stableId|原句|短释义|撰写日期）。

    只登记已逐条写好的释义；未登记的句子 shortMeaning 保持空串，程序不自动补、不臆造。
    """
    if not SENTENCE_GLOSS.exists():
        return {}
    out: dict[str, dict] = {}
    for ln, line in enumerate(SENTENCE_GLOSS.read_text(encoding="utf-8").splitlines(), 1):
        if not line.strip() or line.startswith("#"):
            continue
        cols = [c.strip() for c in line.split("|")]
        if len(cols) != 4:
            raise SystemExit(f"{SENTENCE_GLOSS}:{ln} 列数应为 4，实际 {len(cols)}")
        stable_id, text, meaning, authored = cols
        if not (stable_id and text and meaning and authored):
            raise SystemExit(f"{SENTENCE_GLOSS}:{ln} 有空列")
        if stable_id in out:
            raise SystemExit(f"{SENTENCE_GLOSS}:{ln} 重复 stableId：{stable_id}")
        out[stable_id] = {"text": text, "shortMeaning": meaning, "authoredOn": authored}
    return out


def load_sentence_revisions() -> dict[str, int]:
    """已发布释义的后续修订号；不改变四列释义表的既有格式。"""
    path = EDITORIAL / "sentence-gloss-revisions.psv"
    revisions = {}
    if path.exists():
        for number, line in enumerate(path.read_text(encoding="utf-8").splitlines(), 1):
            if not line.strip() or line.startswith("#"):
                continue
            fields = line.split("|")
            if len(fields) != 2 or not fields[1].isdigit() or int(fields[1]) < 3 or fields[0] in revisions:
                raise SystemExit(f"{path}:{number} 无效或重复的释义修订号")
            revisions[fields[0]] = int(fields[1])
    return revisions


def apply_sentence_meanings(entries: list[dict], meanings: dict[str, dict]) -> list[dict]:
    """按稳定 ID 挂上原创句意短释义：原文与来源不动，只填 shortMeaning、升 recordVersion。"""
    by_id = {e["id"]: e for e in entries}
    applied = []
    revisions = load_sentence_revisions()
    if revisions.keys() - meanings.keys():
        raise SystemExit("释义修订表包含未登记的句子")
    for stable_id, m in meanings.items():
        e = by_id.get(stable_id)
        if e is None:
            raise SystemExit(f"释义目标 id 不在包内：{stable_id}")
        if e["kind"] != "sentence":
            raise SystemExit(f"释义目标不是句子：{stable_id}")
        if e["text"] != m["text"]:
            raise SystemExit(f"释义表原句与包内正文不一致：{stable_id}\n  表内：{m['text']}\n  包内：{e['text']}")
        gloss = m["shortMeaning"]
        if not (SENTENCE_GLOSS_LEN[0] <= len(gloss) <= SENTENCE_GLOSS_LEN[1]):
            raise SystemExit(f"句释义长度越界（{len(gloss)}）：{stable_id}")
        if RE_LATIN.search(gloss) or RE_WEIRD.search(gloss) or not RE_HAN.search(gloss):
            raise SystemExit(f"句释义含不允许的字形：{stable_id}")
        if e["shortMeaning"] not in ("", gloss):
            raise SystemExit(f"句释义与包内既有释义冲突：{stable_id}")
        e["shortMeaning"] = gloss
        e["recordVersion"] += 1                 # 新增释义即一条记录变更
        if stable_id in revisions:
            if revisions[stable_id] < e["recordVersion"]:
                raise SystemExit(f"释义修订号低于正文修订号：{stable_id}")
            e["recordVersion"] = revisions[stable_id]
        if not any(s["type"] == "original" for s in e["sources"]):
            e["sources"].append(src_sentence_gloss("data/editorial/sentences.psv"))
        applied.append({"id": stable_id, "text": e["text"], "shortMeaning": gloss,
                        "recordVersion": e["recordVersion"], "authoredOn": m["authoredOn"]})
    return applied

# ---------------------------------------------------------------- 分类
def classify(text: str, table, default: str) -> tuple[str, bool]:
    scores = Counter()
    for cat, words in table:
        for w in words:
            if w in text:
                scores[cat] += len(w)
    if not scores:
        return default, False
    ranked = scores.most_common(2)
    if len(ranked) > 1 and ranked[0][1] - ranked[1][1] < 2:
        return default, False       # 两类接近时不猜，归中性
    return ranked[0][0], True


# ---------------------------------------------------------------- 词条
def build_words(reg: Registry, editorial: dict, cedict_index: dict) -> list[dict]:
    entries = []
    for text in sorted(editorial):
        g = editorial[text]
        gloss = g["shortMeaning"]
        if not RE_HAN_ONLY.match(text) or len(text) not in WORD_LEN:
            REJECT_RULES["word_shape"] += 1
            continue
        if not (GLOSS_LEN[0] <= len(gloss) <= GLOSS_LEN[1]):
            REJECT_RULES["word_gloss_length"] += 1
            continue
        if RE_LATIN.search(gloss) or RE_WEIRD.search(gloss) or not g["category"]:
            REJECT_RULES["word_gloss_shape"] += 1
            continue
        key = f"word|{norm_key(text)}"
        is_classic = len(text) == 4 and g["file"].endswith("idioms.psv")
        tags = list(g["tags"])
        if "writing" not in tags and (is_classic or "书面" in tags):
            tags.append("writing")
        if "daily" not in tags and not is_classic:
            tags.append("daily")
        sources = []
        if text in cedict_index:
            sources.append(src_cedict(cedict_index[text]))
        sources.append(src_original(g["file"]))
        entries.append({
            "id": reg.get("word", key),
            "recordVersion": 1,
            "kind": "word",
            "text": text,
            "shortMeaning": gloss,
            "genre": "classic" if is_classic else "practical",
            "category": g["category"],
            "tags": tags,
            "usageNote": g["usageNote"],
            "publicationStatus": "published",
            "sources": sources,
        })
    return entries


# ---------------------------------------------------------------- 实用句
def practical_ok(text: str) -> tuple[bool, str]:
    low = text.lower()
    for term in NAME_TERMS:
        if term in text:
            return False, "name_placeholder"
    for term in META_TERMS:
        if term in low:
            return False, "meta_language_sentence"
    for term in PLACEHOLDER_TERMS:
        if term in text:
            return False, "placeholder"
    for term in INAPPROPRIATE_TERMS:
        if term in text:
            return False, "inappropriate_content"
    for term in VULGAR_TERMS:
        if term in text and han_len(text) <= 12:
            return False, "low_value_filler"
    if text.count("的") >= 4 and han_len(text) >= 18:
        return False, "likely_translationese"      # 长句里"的"密集，翻译腔嫌疑（启发式，非判定）
    return True, ""


def build_practical(reg: Registry, path: Path) -> list[dict]:
    entries = []
    seen_loose = set()
    for line in path.read_text(encoding="utf-8").splitlines():
        if not line.strip():
            continue
        c = json.loads(line)
        text = c["text"]
        ok, rule = practical_ok(text)
        if not ok:
            REJECT_RULES[f"sentence_{rule}"] += 1
            continue
        lk = loose_key(text)
        if lk in seen_loose:
            REJECT_RULES["sentence_near_duplicate"] += 1
            continue
        seen_loose.add(lk)
        ref = c["sourceRef"]
        owner = ref.get("owner") or ""
        cat, confident = classify(text, PRACTICAL_CATEGORIES, "日常交流")
        if not confident:
            cat = "日常交流"
        entries.append({
            "id": reg.get("sentence", f"sentence|{norm_key(text)}"),
            "recordVersion": 1,
            "kind": "sentence",
            "text": text,
            "shortMeaning": "",
            "genre": "practical",
            "category": cat,
            "tags": ["daily"],
            "usageNote": "",
            "publicationStatus": "published",
            "sources": [src_tatoeba(str(ref["tatoebaSentenceId"]),
                                    owner if owner not in ("", "\\N") else "",
                                    bool(ref.get("cc0")))],
        })
    return entries


# ---------------------------------------------------------------- 古典句
def segments(paragraphs: list[str]) -> list[str]:
    out = []
    for para in paragraphs:
        for seg in re.findall(rf"[^{HAN}]*?([{HAN}][{HAN}{re.escape(CJK_PUNCT)}]*[。！？])", para):
            seg = seg.strip()
            if re.search(r"[。！？][。！？]", seg):
                REJECT_RULES["classic_double_punctuation"] += 1
                continue
            if SENT_LEN[0] <= han_len(seg) <= SENT_LEN[1] and RE_SENT_END.search(seg):
                out.append(seg)
    return out


def build_classic(reg: Registry) -> list[dict]:
    entries = []
    seen = set()
    repo = "https://github.com/chinese-poetry/chinese-poetry/blob/master/"
    # 繁简只判别不转换：用 CC-CEDICT 繁/简用字差集做启发式判定
    trad_only = trad_only_chars(
        read_cedict(RAW / "cc-cedict" / SNAPSHOT / "cedict_1_0_ts_utf-8_mdbg.txt.gz")[0])
    def add(pid, title, author, seg, url, edition_name, dynasty, note=""):
        key = f"sentence|{norm_key(seg)}"
        if key in seen:
            REJECT_RULES["classic_duplicate"] += 1
            return
        seen.add(key)
        cat, confident = classify(seg, CLASSIC_CATEGORIES, "古典诗词")
        entries.append({
            "id": reg.get("sentence", key),
            "recordVersion": 1,
            "kind": "sentence",
            "text": seg,
            "shortMeaning": "",
            "genre": "classic",
            "category": cat if confident else "古典诗词",
            "tags": ["writing"],
            "usageNote": note,
            "publicationStatus": "published",
            "sources": [src_classic(pid, title, author, url, edition_name, dynasty)],
        })

    # 1) 水墨唐诗（同仓精选，简体）
    for i, poem in enumerate(json.loads((RAW / "chinese-poetry" / SNAPSHOT / "shuimotangshi.json")
                                        .read_text(encoding="utf-8"))):
        title = (poem.get("title") or "").strip()
        author = (poem.get("author") or "").strip()
        if not title or not author:
            REJECT_RULES["classic_missing_meta"] += 1
            continue
        for seg in segments(poem.get("paragraphs") or []):
            add(f"shuimo-{i + 1}", title, author, seg,
                repo + "%E6%B0%B4%E5%A2%A8%E5%94%90%E8%AF%97/shuimotangshi.json",
                "水墨唐诗", "唐代")

    # 2) 宋词三百首（同仓精选，简体；词牌作题名）
    for i, ci in enumerate(json.loads((RAW / "chinese-poetry" / SNAPSHOT / "song300.json")
                                      .read_text(encoding="utf-8"))):
        title = (ci.get("rhythmic") or "").strip()
        author = (ci.get("author") or "").strip()
        if not title or not author:
            REJECT_RULES["classic_missing_meta"] += 1
            continue
        for seg in segments(ci.get("paragraphs") or []):
            add(f"song300-{i + 1}", title, author, seg,
                repo + "%E5%AE%8B%E8%AF%8D/%E5%AE%8B%E8%AF%8D%E4%B8%89%E7%99%BE%E9%A6%96.json",
                "宋词三百首", "宋代")

    # 3) 全唐诗分卷：只取李白/杜甫/白居易三位（用户点名的定向补强），排除郊庙乐章等仪式套曲
    CORE_POETS = ("李白", "杜甫", "白居易")
    ritual = ("郊廟", "郊庙", "樂章", "乐章", "導引", "导引", "挽歌", "挽辭", "挽辞",
              "享先農", "享先农", "釋奠", "释奠", "封禪", "封禅", "輓歌", "輓辭", "哀辭", "哀辞")
    for pf in sorted((RAW / "chinese-poetry" / SNAPSHOT).glob("poet.tang.*.json")):
        for poem in json.loads(pf.read_text(encoding="utf-8")):
            title = (poem.get("title") or "").strip()
            author = (poem.get("author") or "").strip()
            if not title or not author:
                REJECT_RULES["classic_missing_meta"] += 1
                continue
            if not any(f in author for f in CORE_POETS):
                continue
            if any(r in title for r in ritual):
                continue
            for seg in segments(poem.get("paragraphs") or []):
                trad = any(ch in trad_only for ch in seg)
                add(f"{pf.stem}-{poem.get('id', '')}", title, author, seg,
                    repo + "%E5%85%A8%E5%94%90%E8%AF%97/" + pf.name,
                    f"全唐诗 {pf.name}", "唐代",
                    "原文用字保留当时字形，未作繁简转换。" if trad else "")
    return entries


def main(argv: list[str] | None = None) -> int:
    ap = argparse.ArgumentParser()
    ap.add_argument("--sample", type=int, default=30, help="抽样条数写入 metadata/release-sample.jsonl")
    args = ap.parse_args(argv)

    reg = Registry()
    editorial = load_editorial()

    # CEDICT 词形索引（仅用于署名定位，不引用其释义）
    cedict_index = {}
    for path in PROCESSED.glob("entries.words.candidate.jsonl"):
        for line in path.read_text(encoding="utf-8").splitlines():
            if not line.strip():
                continue
            c = json.loads(line)
            trad = c.get("traditionalForm") or c["text"]
            cedict_index[c["text"]] = f"{trad} {c['text']} [{c['pinyin']}]"

    words = build_words(reg, editorial, cedict_index)
    practical = build_practical(reg, PROCESSED / "entries.sentences.practical.candidate.jsonl")
    classic = build_classic(reg)

    # 跨来源去重：同形文本只保留一条（词优先，其次实用句），避免同一张卡重复出现
    word_keys = {norm_key(w["text"]) for w in words}
    practical_keys = {norm_key(p["text"]) for p in practical}
    before = len(classic)
    classic = [c for c in classic
               if norm_key(c["text"]) not in word_keys
               and norm_key(c["text"]) not in practical_keys]
    if len(classic) != before:
        REJECT_RULES["classic_collides_with_word_or_practical"] += before - len(classic)

    entries = words + practical + classic
    corrections = load_corrections()
    corrections_applied = apply_corrections(entries, corrections)
    sentence_meanings = load_sentence_meanings()
    sentence_applied = apply_sentence_meanings(entries, sentence_meanings)
    missing_meanings = sum(e["kind"] == "sentence" and not e["shortMeaning"].strip() for e in entries)
    if missing_meanings:
        raise SystemExit(f"v4 全量释义尚未就绪，仍缺 {missing_meanings} 条，正式包保持不变")
    entries.sort(key=lambda e: (e["kind"] != "word", e["genre"], e["category"], e["text"]))

    ids = [e["id"] for e in entries]
    if len(set(ids)) != len(ids):
        raise SystemExit("重复 id，停止生成")
    texts = Counter((e["kind"], e["text"]) for e in entries)
    dup_text = [t for t, n in texts.items() if n > 1]
    if dup_text:
        raise SystemExit(f"校改后出现重复正文：{dup_text[:3]}")

    payload = {
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "datasetId": DATASET_ID,
        "contentVersion": CONTENT_VERSION,
        "entries": entries,
    }
    PROCESSED.mkdir(parents=True, exist_ok=True)
    OUT_PATH.write_text(json.dumps(payload, ensure_ascii=False, separators=(",", ":")) + "\n",
                        encoding="utf-8")
    reg.save()

    # ---- 统计
    cat_dist = Counter(e["category"] for e in entries)
    genre_dist = Counter(e["genre"] for e in entries)
    kind_genre = Counter(f"{e['kind']}:{e['genre']}" for e in entries)
    tag_dist = Counter(t for e in entries for t in e["tags"])
    report = {
        "builtAt": now_iso(),
        "format": FORMAT,
        "schemaVersion": SCHEMA_VERSION,
        "datasetId": DATASET_ID,
        "contentVersion": CONTENT_VERSION,
        "path": str(OUT_PATH.relative_to(DATA)).replace("\\", "/"),
        "bytes": OUT_PATH.stat().st_size,
        "counts": {
            "entries": len(entries),
            "words": len(words),
            "sentencesPractical": len(practical),
            "sentencesClassic": len(classic),
            "sentencesWithMeaning": len(sentence_applied),
            "sentencesWithoutMeaning": len(practical) + len(classic) - len(sentence_applied),
        },
        "kindGenre": dict(kind_genre),
        "genre": dict(genre_dist),
        "categories": dict(cat_dist.most_common()),
        "tags": dict(tag_dist.most_common()),
        "filteredCounts": dict(REJECT_RULES.most_common()),
        "correctionsApplied": corrections_applied,
        "sentenceMeaningsApplied": sentence_applied,
        "newIdsAssigned": reg.new_count,
        "registryEntries": len(reg.map),
        "evidenceStatus": "全部条目 evidenceStatus=source_checked：原文与登记的公开来源快照匹配，未做人工逐条核验",
        "limits": [
            "句子短释义覆盖 %d 条（data/editorial/sentences.psv），未覆盖 %d 条；主代理只作抽样检查，不代表全库逐条人工审校。"
            % (len(sentence_applied), len(practical) + len(classic) - len(sentence_applied)),
            "句释义只按原文字面重述句意，不代表全篇主旨，也未经第三方注本校订。",
            "Tatoeba 句名占位/不宜内容/近似重复已过滤，但翻译腔与自然度仍靠抽样，不是全量判定。",
            "古典句保留原字形（含繁体），未做繁简转换，usageNote 已标注需注意字形的情况。",
            "词语释义为编辑撰写共 643 条规模，未做第三方专业词典校订。",
        ],
    }
    (METADATA / "release_report.json").write_text(
        json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")

    # 抽样（给主代理/编辑抽看，不替代核验）
    import random
    random.seed(20261024)
    picks = (random.sample(words, min(args.sample // 3, len(words)))
             + random.sample(practical, min(args.sample // 3, len(practical)))
             + random.sample(classic, min(args.sample - 2 * (args.sample // 3), len(classic))))
    (METADATA / "release-sample.jsonl").write_text(
        "".join(json.dumps(p, ensure_ascii=False) + "\n" for p in picks), encoding="utf-8")

    print(json.dumps(report["counts"], ensure_ascii=False))
    print(json.dumps({"kindGenre": report["kindGenre"], "bytes": report["bytes"],
                      "newIds": reg.new_count}, ensure_ascii=False))
    print(json.dumps(report["filteredCounts"], ensure_ascii=False))
    print(json.dumps({k: v for k, v in list(report["categories"].items())}, ensure_ascii=False))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())