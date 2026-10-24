# practical-c 批次说明（固定清单 practical-assignment-3）

- 覆盖：固定清单 `practical-assignment-3.jsonl` 全部 7317 条，`batch_001.jsonl`–`batch_037.jsonl`（36×200 + 1×117），顺序与清单一致。
- 行结构：`{"id","text","shortMeaning","authoredOn"}`，`id`/`text` 逐字取自清单，`authoredOn` 均为 `2026-10-24`。
- 释义由模型逐条撰写，简体现代汉语，保留否定、条件、数量、时间、人物关系与问句/请求/命令语气；不复制原文，不加空壳套话。
- 未产生 `uncertainties.jsonl`：无判断不了的条目。本目录未回写正式包/PSV/scripts/docx/app/img。

## 自检结果（2026-10-24）

- 行数 7317，ID 唯一 7317，清单覆盖 7317，顺序一致。
- 字段集合、日期、长度 6–60、无拉丁字母/网址/竖线、无与原文同句、无跨批重复释义、无“这句话/意思是/出自/体现了/表示/比喻/本句/该句/用来形容”等空壳词：0 错误。
- 长度分布：<8 字 202 条，8–9 字 1619 条，10–11 字 2182 条，12–40 字 3312 条，>40 字 2 条（最长 42）；中位数 11 字。原文多为短句，释义偏简洁；如需要更高的 12–40 字占比，可按批次扩写短释义，不影响 ID/text 对齐。

## 续跑/复核

在本目录重跑校验（仓库根目录执行）：

```python
import json, glob
src = [json.loads(l) for l in open('<清单路径>/practical-assignment-3.jsonl', encoding='utf-8') if l.strip()]
out = [json.loads(l) for fp in sorted(glob.glob('data/editorial/sentence-gloss-batches/practical-c/batch_*.jsonl'))
       for l in open(fp, encoding='utf-8') if l.strip()]
assert [o['id'] for o in out] == [r['id'] for r in src]
assert all(o['shortMeaning'].strip() != o['text'].strip() for o in out)
print(len(out))
```

同批稿由其他执行者分别写在 `practical/`、`classic/` 等目录，本目录不涉及、不覆盖。
