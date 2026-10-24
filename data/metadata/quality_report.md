# 数据准备报告（自动生成，未人工逐条核验）

生成时间：2026-10-24T09:21:03Z　规则版本：yanjian-gate/1　内容版本：`0.1.0-candidate.1`
本轮只产出候选（`publicationStatus=candidate`），不构成发布包，也不代表正式 schema 已定稿。

## 1. 输入快照与哈希（传输完整性，非内容签名）

| 文件 | 字节 | sha256（前 16） |
| --- | --- | --- |
| `raw/cc-cedict/2026-10-24/cedict_1_0_ts_utf-8_mdbg.txt.gz` | 3981626 | `46e34d0555e18087` |
| `raw/chinese-poetry/2026-10-24/poet.tang.0.json` | 408405 | `4f88f42993808252` |
| `raw/chinese-poetry/2026-10-24/poet.tang.1000.json` | 477519 | `6e703b8d8dcedbd6` |
| `raw/chinese-poetry/2026-10-24/poet.tang.2000.json` | 417948 | `ec28efa7e2ae9cdb` |
| `raw/chinese-poetry/2026-10-24/poet.tang.3000.json` | 453811 | `9e4ba2323752b6b9` |
| `raw/chinese-poetry/2026-10-24/poet.tang.4000.json` | 488655 | `b8a7946c879b9658` |
| `raw/chinese-poetry/2026-10-24/poet.tang.5000.json` | 467225 | `a0306e6962cff016` |
| `raw/chinese-poetry/2026-10-24/poet.tang.6000.json` | 470031 | `982a21d2b15922b4` |
| `raw/chinese-poetry/2026-10-24/poet.tang.7000.json` | 474558 | `a6fb69d7b4343c90` |
| `raw/chinese-poetry/2026-10-24/shuimotangshi.json` | 95878 | `e175adf044f015cd` |
| `raw/chinese-poetry/2026-10-24/song300.json` | 151283 | `ca5d74f7fdb9d5a6` |
| `raw/jieba-dict/2026-10-24/dict.txt.big` | 8583143 | `b16011275c42955c` |
| `raw/tatoeba/2026-10-24/cmn_sentences_CC0.tsv.bz2` | 102 | `72c6ac699497cbc9` |
| `raw/tatoeba/2026-10-24/cmn_sentences_detailed.tsv.bz2` | 1725873 | `7af009b234bbb21e` |

## 2. 各段通过量

| 阶段 | 数量 |
| --- | --- |
| `cedict_wordforms` | 121545 |
| `cedict_entries_total` | 125310 |
| `cedict_word_entries_2char` | 62346 |
| `cedict_word_entries_4char` | 19255 |
| `words_candidates` | 50227 |
| `words_glossed` | 635 |
| `words_candidates_2char` | 39053 |
| `words_candidates_4char` | 11174 |
| `words_glossed_2char` | 463 |
| `words_glossed_4char` | 172 |
| `tatoeba_rows` | 89211 |
| `tatoeba_cc0_ids` | 1 |
| `sentences_practical` | 29180 |
| `sentences_practical_traditional_deferred` | 26169 |
| `sentences_classic` | 728 |
| `poem_files` | 8 |
| `sentences_classic_traditional_deferred` | 37384 |
| `words_frequency_override_by_editorial` | 6 |
| `sentences_total` | 29908 |

## 3. 词条闸门（按规则计数，全量）

| 规则 | 拒绝数 |
| --- | --- |
| `word_no_usage_frequency` | 25156 |
| `word_length_3_not_2_or_4` | 24347 |
| `word_length_1_not_2_or_4` | 10567 |
| `word_def_reference_only` | 4356 |
| `word_length_5_not_2_or_4` | 2830 |
| `word_length_6_not_2_or_4` | 1102 |
| `word_not_han_only` | 1068 |
| `word_def_proper_noun_only` | 719 |
| `word_length_7_not_2_or_4` | 681 |
| `word_length_8_not_2_or_4` | 238 |
| `word_length_9_not_2_or_4` | 94 |
| `word_length_10_not_2_or_4` | 77 |
| `word_length_11_not_2_or_4` | 30 |
| `word_def_noise` | 26 |
| `word_length_12_not_2_or_4` | 11 |
| `word_length_13_not_2_or_4` | 8 |
| `word_length_14_not_2_or_4` | 5 |
| `word_length_15_not_2_or_4` | 2 |
| `word_length_19_not_2_or_4` | 1 |

## 4. 实用句闸门（Tatoeba cmn）

| 规则 | 拒绝数 |
| --- | --- |
| `sentence_too_short` | 28140 |
| `sentence_too_repetitive` | 2252 |
| `sentence_not_complete` | 1647 |
| `sentence_contains_latin` | 1505 |
| `sentence_too_long` | 171 |
| `sentence_safety_keyword_heuristic` | 84 |
| `sentence_duplicate` | 22 |
| `sentence_weird_script` | 21 |
| `sentence_ellipsis_placeholder` | 17 |
| `sentence_contains_url` | 3 |

## 5. 古典句闸门（全唐诗，公有领域原文）

| 规则 | 拒绝数 |
| --- | --- |
| `classic_duplicate` | 1794 |
| `sentence_too_short` | 464 |
| `sentence_too_repetitive` | 60 |
| `sentence_safety_keyword_heuristic` | 1 |
| `classic_missing_meta` | 1 |

## 6. 编辑原创释义

- 原创词条释义文件：`data/editorial/words.psv`（466 条）、`data/editorial/idioms.psv`（177 条）
- 与 CC-CEDICT 词形匹配：635 条
- 未在 CC-CEDICT 找到（本轮未采用）：8 条
- 匹配到词形但未过闸门：0 条
- 释义为人工撰写的一句现代汉语，不是程序模板拼接；条数即实际撰写条数。

## 7. 许可与署名

- CC-CEDICT：CC BY-SA 4.0（数据许可），仅使用词形/拼音/英文释义字段；中文短释义为原创，仍需在上线时保留 CC-CEDICT 署名与许可链接，并注明修改。
- Tatoeba：逐句许可，1 条 CC0 候选集中实际可用句以 `sourceRef.cc0` 标记，其余按 CC BY 2.0 FR 署名到具体 contributor。
- chinese-poetry：仓库整理层 MIT（代码/整理许可），唐代诗作原文属公有领域；未使用任何现代译文、注释。
- jieba 词典：仅作词频信号（`selection_signal_only`），其文本不进入候选或发布包。
- chinese-xinhua：不入库（仓库 MIT 不能证明逐条释义/例句的再发布权）。
- 代码许可与数据许可分开登记，见 `data/sources.json` 的 `codeLicense*` 与 `license*` 字段。

## 8. 已知限制（不能当作安全或质量全覆盖）

1. 规则闸门只覆盖结构、长度、繁简用字、重复、网址/拉丁字母、显性不宜词（32 条关键词）与脚本字符；**不是内容安全全覆盖**，语义得当、真实性、翻译腔仍需人工/模型二次筛选。
2. 未做繁简转换：疑似繁体句不转换后当原文，默认不写盘，只在统计里计数（`*_traditional_deferred`）。繁简判定是启发式（CC-CEDICT 繁/简用字差集），个别两体同形的字（如「徵」）会漏判，正式发布前需权威繁简判定。
3. 实用句按 `norm_key` 去重（去标点/空白），近义句只做精确去重，未聚类。
4. 词条闸门对同形多义只保留一个代表条目（先排除"变体/参见"类条目，再取释义最多者），义项细分待编辑决策。
5. `evidenceStatus=located` 仅表示该原文能在登记的下载快照中定位，不等于人工逐条核验，也不等于发布审批通过。
6. 缺中文短释义的词条与全部句子仍是"候选"，不满足成品要求。
7. Tatoeba 语料含翻译腔与人名句（汤姆、玛丽等），闸门不判断自然度；`sentence_too_short` 已过滤 8 字以下短句，但"自然、值得学"仍需编辑或模型二次筛选。
8. 古典句取自全唐诗 8 个分卷（约 8,000 首），含较多郊庙歌辞等冷僻篇目；是否适合入门需编辑挑选，未经挑选不宜直接发布。
9. 词条候选池按"CC-CEDICT 词形 + 通用语料频次"筛出，仍含低价值专名与生僻术语（如"颖果""肛裂""北韩"）；这些词没有中文释义，标 `missing_short_meaning`，不是成品。
