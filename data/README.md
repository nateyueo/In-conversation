# data/ —— 言间内容数据（候选池 + 正式内容包）

## 0. 状态一句话

- **正式包**：`data/processed/official-content.json`（30,278 条，`contentVersion=3`，`publicationStatus=published`），按固定契约输出，可离线分发。
- **候选池**：`data/processed/entries.*.candidate.jsonl` 等，仅供编辑扩充与重跑，**已 git 忽略**，不代表正式数量。
- 所有条目的 `evidenceStatus=source_checked` 只表示"原文与登记的公开来源快照匹配"；处理方式是**自动过滤 + 抽样查看**，**不是**全部条目经编辑逐条批准，也**不是**影视级别的严格 verified。

## 1. 正式包契约（不可随意改）

```jsonc
{
  "format": "yanjian-official-content",
  "schemaVersion": 1,
  "datasetId": "yanjian-text",
  "contentVersion": 3,
  "entries": [ { "id": "official:word:<uuid4>" | "official:sentence:<uuid4>",
                 "recordVersion": 1 | 2 | 3, "kind": "word" | "sentence",
                 "text": "…", "shortMeaning": "…", "genre": "practical" | "classic",
                 "category": "非空领域名", "tags": ["daily" | "writing", …],
                 "usageNote": "…或空串", "publicationStatus": "published",
                 "sources": [ { "id", "type", "title", "quote", "locator", "url", "edition",
                                "licenseName", "licenseUrl", "attribution",
                                "evidenceStatus", "rightsStatus" } ] } ]
}
```

- `type` ∈ `dictionary` / `community_sentence` / `public_domain_text` / `original` / `film`（本轮无 film）。
- `rightsStatus` ∈ `open_license` / `public_domain` / `original`；`evidenceStatus` ∈ `source_checked` / `verified`。
- 句子 `shortMeaning`：已逐条写好原创短释义的句子为**非空**（6–60 字现代汉语一句话释义，来源里追加 `type=original` 的「言间原创中文句意短释义」块）；其余句子仍为空串 `""`（表示尚未释义），程序不自动补、不臆造；这类句子翻面提示「释义待补充」，可阅读收藏但不参与评分。
- `usageNote` 允许空串；仅当古典原文含繁体/异体字形时写入说明，不编造语境。

## 2. 数量（以 `metadata/release_report.json` 与 `metadata/release_validation.json` 为准）

| 类别 | 正式包 | 候选池（参考，不入库） |
| --- | --- | --- |
| 条目总数 | **30,278** | — |
| 词语 | **643**（两字 466 通用 + 四字 177 成语/固定表达） | 词形候选 50,227（其中带原创释义 635） |
| 实用句 | **26,011**（Tatoeba 普通话） | 29,180 |
| 古典句 | **3,624**（唐诗宋词精选 + 李白/杜甫/白居易） | 728（未挑选的全唐诗简体切片） |
| 句子短释义 | **83**（编辑原创，`editorial/sentences.psv`）；其余 29,552 条 `shortMeaning` 为空串 | — |
| 包体大小 | 23,338,610 字节（约 22.3 MiB） | — |

`genre` 分布：practical 26,477 / classic 3,801（**不是**按 60:40 配比强行凑数，60:40 是日后推荐采样比例，不是库存硬指标）。

主要 `category`（领域名）：日常交流 19,343、古典诗词 3,269、时间与安排 1,199、学习与工作 1,152、家庭与朋友 1,131、情感与心情 790、请求与建议 602、出行与地点 531、饮食与生活 354、健康与身体 340、问候与礼貌 255、天气与环境 191、写景状物 127、购物与金钱 123，另有词语领域（评价 95、人际 89、表达 63、情感 60…）与古典细类（送别思乡 82、抒情言志 51、人生感怀 48、咏史怀古 28、边塞征战 19）。分类靠关键词打分，只有明显领先（差值 ≥2）才落类，否则归中性。

## 3. 命令（全部可重跑）

```powershell
# 1) 采集（有界下载 + sha256 + 许可证据；已有同哈希文件自动跳过）
python data/scripts/fetch.py --scratch-dir $env:PI_SCRATCH_DIR

# 2) 候选池（raw → 候选 JSONL + 统计 + 稳定 ID 注册）
python data/scripts/build_candidates.py
python data/scripts/validate.py                 # 期望 {"result": "PASS"}

# 3) 正式包（候选 + 原创释义 + 精选题集 → official-content.json）
python data/scripts/build_release.py            # 可加 --sample N
python data/scripts/validate_release.py         # 期望 {"result": "PASS"}

# 4) 繁体原文（默认不写盘）
python data/scripts/build_candidates.py --include-traditional
```

两次重跑 `official-content.json` 字节完全一致；候选重建后再跑正式包，ID 也不变（`metadata/id_registry.jsonl.gz` 是唯一权威登记表，`official:<kind>:<uuid4>`）。

## 4. 来源与许可（数据许可 ≠ 代码许可）

| 来源 | 数据/内容许可 | 代码/整理层许可 | 用于 | 包内署名 |
| --- | --- | --- | --- | --- |
| CC-CEDICT | CC BY-SA 4.0 | — | 词形与拼音（词语 635 条） | 每条 `sources[0]` 带许可名/链接/修改说明 |
| Tatoeba（cmn） | 逐句 CC BY 2.0 FR，少数 CC0 1.0 | — | 实用句 26,011 条 | 每条带原 `sentenceId`、contributor、许可名与链接 |
| chinese-poetry | 唐诗宋词原文属公有领域 | MIT（整理层） | 古典句 3,624 条 | 每条约书名/作者/仓库地址/许可 |
| 本项目原创释义 | 言间自制内容 | 仓库代码许可不适用 | 词语释义、`type=original` 来源 | `attribution` 明示自制、非第三方词典 |

- 包内 `sources[]` 自带许可名、许可链接与署名，**离线可用**，不依赖外网；`processed/attribution.sources.jsonl` 是来源级汇总。
- CC BY-SA 4.0 内容（词形/拼音）保留署名与许可链接，并注明中文释义为原创替换；本项目原创释义单独声明许可，两者不混写。
- 仓库代码许可与上述数据许可分开：`sources.json` 的 `codeLicense*` 与 `license*` 字段分别登记。
- 未入库：chinese-xinhua（仓库 MIT 不能证明逐条释义/例句的再发布权）。

## 5. 筛选与质量规则（两段式）

**第一段（候选闸门，`build_candidates.py`）**：词长 2/4 字、纯汉字、CEDICT 释义非"变体/参见"、通用语料有频次证据、排除仅繁体用字；句子纯中文、无拉丁字母/网址/全角空格、汉字 8–60、句末有标点、去重、显性不宜词关键词过滤。

**第二段（发布筛选，`build_release.py`，计数见 `release_report.json`）**：
- 实用句：人名占位（汤姆/玛丽等 28 个）2,673 条、不宜内容（死/杀/暴力等）342 条、近似重复（去"了/的/些/一"等松散键）71 条、翻译腔嫌疑（长句"的"≥4）70 条、占位词 7 条、低价值语气词 4 条、元语言句 2 条。
- 古典句：重复 60 条、重复句末标点残留 11 条、缺题名/作者 1 条、与词条同形 1 条；并排除郊庙乐章/挽歌等仪式套曲，全唐诗只取李白/杜甫/白居易。
- 词语：643 条全部为人工撰写释义并通过 6–60 字长度与纯汉字检查（本轮修正 29 条过短释义、2 条被批处理合并损坏的行、1 条超短释义）。

## 6. 已知局限（不要当作"全部人工核验"）

1. 没有编辑逐条批准：只有自动过滤 + 抽样查看（`metadata/release-sample.jsonl`）。句子短释义目前只覆盖 83 条（`editorial/sentences.psv`），其余 29,552 条句子仍无中文释义。
2. Tatoeba 句仍有翻译腔、书面腔与不自然表达，翻译腔只做了"长句的≥4"这类启发式判断，不是判定器。
3. 分类是关键词启发式，74% 实用句与 90% 古典句落在中性类（`日常交流` / `古典诗词`），不等于已做主题标注。
4. 古典句保留原字形，**未经权威繁简判定**；含繁体用字者 `usageNote` 已注明"保留当时字形，未作繁简转换"，但个别两体同形字可能漏判。
5. 词语释义规模 643 条，未经第三方专业词典校订；`tags` 中保留编辑原始标签（中性/褒义/书面等），另加 `daily`/`writing`。
6. 未做：语义五维评分、近义聚类、人工逐条核验、法务意见；`evidenceStatus` 仅 `source_checked`。

## 8. 正文校改（v2 起）

- 校改表：`data/corrections/text-corrections.psv`（`stableId|sourceId|oldText|newText|recordVersion|证据链接|说明|日期`）。
- 规则：**只修已确认的具体字词错误**；ID 由校改表钉住，文字变化**不生成新 ID**，只把该条 `recordVersion` 升到表内值；`raw/` 原文不改；不把单字校改推广到其他同形字句，也不做异体字/版本差异的统一改字。
- 校验：`validate_release.py` 会逐条核对"新正文已生效、旧正文不再出现、recordVersion 已升、来源匹配"；`build_release.py` 会把已应用的校改写入 `metadata/release_report.json` 的 `correctionsApplied`。
- 本轮唯一一条：`official:sentence:428f1f1d-15af-421d-89b1-1d53ba613fe2`（陈与义《临江仙》，底本 chinese-poetry `song300-113`）
  - 原：`万事一身伤老矣，戌葵凝笑墙东。` → 校：`万事一身伤老矣，戎葵凝笑墙东。`
  - 依据：四库本《簡齋集》影印页 https://zh.wikisource.org/wiki/Page%3ASibu_Congkan1050-%E9%99%B3%E8%88%87%E7%BE%A9-%E5%A2%9E%E5%BB%A3%E7%AE%8B%E8%A8%BB%E7%B0%A1%E9%BD%8B%E8%A9%A9%E9%9B%86-4-4.djvu/122（页面含「萬事一身傷老矣戎葵」）；核对记录见 `data/evidence/correction-sentence-428f1f1d-15af-421d-89b1-1d53ba613fe2.txt`。
  - 边界：只改「戌→戎」一字；「凝笑」等版本差异保持原样，不做统一。
- 只要校改改变正文，顶层 `contentVersion` 必须加 1（v1 → v2），以便已加载旧版的工程能识别并更新；本次总量与其他条目均未变。

## 9. 仓库里保留什么

`git` 保留：`scripts/`、`sources.json`、`evidence/`（许可原文与 CC 许可文本）、`editorial/`（原创释义）、`corrections/`（正文校改表）、`metadata/`（稳定 ID 登记表压缩件、统计、报告、抽样）、`processed/official-content.json`、`processed/attribution.sources.jsonl`、`README.md`。

`git` 忽略（可重跑再生）：`raw/`、`processed/entries.*.jsonl`、`processed/attribution.owners.jsonl`、`candidate/`、`metadata/id_registry.jsonl`（未压缩副本）。见 `data/.gitignore`。

## 10. 句子短释义与未覆盖范围（v3 起）

- 表：`data/editorial/sentences.psv`（`stableId|原句|短释义|撰写日期`）。只登记已经逐条写好的释义，可重跑读入。
- 规则：只按**原文字面**把句意写成一句现代汉语；不用"出自《…》"式来源代释义，不套通用套话，不复制任何第三方译文或注本；不确定的不写，宁可留空。
- 接入（`build_release.py`）：按 `stableId` 挂释义，**原句正文与原有来源块一字不动**，只填 `shortMeaning`、把该条 `recordVersion` 加 1，并追加一条 `type=original` / `rightsStatus=original` 的来源块（`id=yanjian-original-sentence-gloss`，`locator=data/editorial/sentences.psv`）。
- 校验（`validate_release.py`）：逐条核对表内 `stableId` 在包内、原句一致、释义已生效、`recordVersion ≥ 2`、附原创来源块；句子短释义长度限 6–60 字、不含拉丁字母。
- 覆盖面：**83 / 29,635 条**（古典 69 条 + 实用 14 条），**仍缺 29,552 条**，其 `shortMeaning` 保持空串，翻面提示「释义待补充」，可阅读收藏但不参与评分。已覆盖含截图句 `official:sentence:000eede8-4120-45fe-b5ec-f09064fd6844`（周密《曲游春》「楚苑东风外，暖丝情絮，春思如织。」）及一批唐诗宋词名句与推荐队列靠前的口语句；**这不等于整个语料已补齐**，也未做逐条第三方校订。
- 因内容变化，顶层 `contentVersion` 由 2 升到 3；条目总量 30,278、全部 `id`、分类与筛选计数均未变，包体 23,338,610 字节。