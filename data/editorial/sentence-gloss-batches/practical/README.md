# 实用句短释义分批稿（practical）

本目录存放 `data/processed/official-content.json` 中 `kind=sentence`、`genre=practical`、`shortMeaning` 为空条目的现代汉语短释义稿。**本目录只是分批草稿，尚未回写正式包**，正式包、`data/editorial/sentences.psv`、脚本、docs、App 均未改动。

## 目录约定

- `practical-NNNN.jsonl`：每批一个文件，按源文件 `entries` 顺序切片，行结构固定为
  `{"id": ..., "text": ..., "shortMeaning": ..., "authoredOn": "YYYY-MM-DD"}`。
- `id` 与 `text` 逐字取自源包，`text` 不得改写；`shortMeaning` 为逐条撰写的简体中文句意。
- `authoredOn` 为撰写当天本地日期（现有批次均为 `2026-10-24`）。
- `uncertainties.jsonl`：确实判断不了的条目（`{"id","text","reason","decidedOn"}`）。**当前尚未产生**，不宜用“待补充/无法解释”充当释义。

## 质量约束

1. 直接给自然简体中文句意，不写“这句话表达了/意思是/出自/体现了”等空壳。
2. 保留否定、条件、时态、数量、人物关系与疑问/请求/命令语气；个人观点仍按说话者观点解释。
3. 不复制原文凑覆盖，不加不存在的隐喻或背景；原句直白时用更明白的近义说法。
4. 长度硬限制 6–60 字符，常规 12–40 字；不含拉丁字母、网址、源元数据。
5. 不与源包已有的 14 条 sentence/practical 释义重复覆盖，也不改这 14 条。

## 进度与续跑

- 目标条目 25,997 条；已落盘见各批次行数合计，剩余量为目标总数减去已落盘数。
- 续跑位置：取 `official-content.json` 中满足上述筛选条件、`shortMeaning` 为空、且 `id` 不在本目录任何 `*.jsonl` 里的条目，按源顺序取下一片即可；无需记录额外游标。
- 参考计算（在仓库根目录执行）：

```python
import json, glob
src = json.load(open('data/processed/official-content.json', encoding='utf-8'))['entries']
done = set()
for fp in glob.glob('data/editorial/sentence-gloss-batches/practical/*.jsonl'):
    if fp.endswith('uncertainties.jsonl'):
        continue
    for line in open(fp, encoding='utf-8'):
        if line.strip():
            done.add(json.loads(line)['id'])
rem = [e['id'] for e in src
       if e.get('kind') == 'sentence' and e.get('genre') == 'practical'
       and not (e.get('shortMeaning') or '').strip() and e['id'] not in done]
print(len(done), len(rem))
```

## 已做检查（2026-10-24）

逐批与全量两轮校验均通过：ID 唯一、ID 均存在于源包、`text` 与源包逐字一致、kind/genre 匹配、未触碰已有释义、字段集合一致、长度全部落在 6–60、无拉丁字母、无跨批重复释义、无空壳词（“这句话/意思是/出自/体现了/比喻”）。
