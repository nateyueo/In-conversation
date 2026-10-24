# practical-a 分批稿（assignment-1 固定 8000 条）

来源清单：`practical-assignment-1.jsonl`（8000 条，ID 唯一，与本目录无交集的其他分配清单 practical-assignment-2/3 不处理）。条目按源清单顺序切片，尚未回写正式包。

## 文件约定

- `practical-a-NNNN.jsonl`：每批约 200 条，行结构 `{"id","text","shortMeaning","authoredOn"}`，`authoredOn` 为撰写当天本地日期（2026-10-24）。
- `uncertainties.jsonl`：确实无法判定所指的条目（`{"id","text","reason","decidedOn"}`），**不作为释义通过**，且其 ID 计为已处理，续跑时不再重复。
- `practical-a-resolved-NNNN.jsonl`：原记入 `uncertainties.jsonl` 的疑难条目在取得原文出处（Tatoeba 原页实际回执等）后另行撰写，行结构同批次文件；其 ID 同样计为已处理。

## 进度与续跑

- 已用批次：见各文件行数合计；未完成量 = 8000 −（各批行数 + uncertainties 行数）。
- 续跑：取 assignment-1 中 ID 不在本目录任何 `*.jsonl`（含 `uncertainties.jsonl`）的条目，按清单顺序取下一片 200 条即可，无需额外游标。

## 逐条自检要求（每批写盘前）

- 释义不得与自身 `text` 完全相同；不得使用“这句话/意思是/出自/体现了”等空壳；不得模板拼原文。
- 长度 6–60 字符；不含拉丁字母；批内与跨批不重复释义；`text` 逐字取自源包，不改写。
- 保留否定、条件、问句/命令语气、人物关系与时间信息（如 23 点写作“晚上十一点”）；不强化、不添加理由或情绪背景；性别未明时不写“他/她”。
- 医药、饮食类句子只解释句意，不扩写成普遍建议。
