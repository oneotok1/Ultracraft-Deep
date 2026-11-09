# i18n 工具

汉化期间写的检查脚本。除了 `i18n_swap.py` 与 `i18n_sentences.py`（一次性代码迁移，
已经跑完），其余几个是随时可以重跑的核对工具，用来回答「还有哪句英文没翻译」。

全部脚本自己定位仓库根目录，从任何目录运行都可以：

    python tools/i18n/i18n_gap.py

## 核对类（可重跑）

| 脚本 | 回答什么 |
|---|---|
| `i18n_gap.py` | Java 里 `Component.translatable` 引用的键，哪些不在 `zh_cn.json` |
| `i18n_keys.py` | 同上，但同时对比 `en_us.json`，给出三份清单 |
| `i18n_cov.py` | 还剩几处纯字面量 `Component.literal("...")`，`zh_cn.json` 覆盖了没有 |
| `i18n_audit.py` | 纯字面量与拼接串（`+`）各多少，列出样本 |
| `i18n_audit2.py` | 源码里所有字符串字面量，哪些在 `zh_cn.json` 里找得到对应译文 |
| `cs_scan.py` | C# 桥接侧（`ultrabridge/*.cs`）有多少含中文的字符串字面量，按文件分布 |
| `cs_dump.py` | 逐条列出 C# 侧的中文字面量与行号，跳过已在总览第六节登记过的 `UpgradesZh.cs` |

`i18n_gap.py` / `i18n_keys.py` 会把 `"boss.ultracraft." + b.key()` 这种拼接前缀
当成完整键报为缺失，那是假阳性——实际键在运行期才拼出来，语言文件里是齐全的。

## 迁移类（一次性，已跑完）

| 脚本 | 做过什么 |
|---|---|
| `i18n_swap.py` | 把纯字面量 `Component.literal("X")` 改成 `Component.translatable("X")`，跳过含 `%` 与纯标点的。命中 75 处、转换 72、跳过 3 |
| `i18n_sentences.py` | 第二遍：把带变量的整句改成 `%s` 占位或 `append` 分段拼装，避免把中文尾巴粘到未经翻译的名字上 |

这两个会直接改源码，重跑前先确认工作区干净。保留它们是因为改动逻辑不直观，
以后要处理新的英文串时可以照着改。
