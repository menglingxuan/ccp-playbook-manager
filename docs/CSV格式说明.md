# CSV 格式说明

面向外部「批量导入工具」的输入/输出约定。本系统导出固定为 **UTF-8**，其他格式由选项控制。

## 1. 列顺序

| `managedColumns` | 列组成 |
| --- | --- |
| `NONE` | 仅交易字段（产品 schema 顺序） |
| `KNOWN` | 交易字段 + `_TestCaseId`（该批次存在时才输出） |
| `ALL` | 按源文件列序：左侧管理列 → 交易字段 → 右侧管理列 |

- 交易字段顺序**固定**为产品定义顺序，与源文件列序无关。
- 重名列（例如两个 `Rate`）按定义顺序原样输出，**不重命名**，外部工具按位置读取。
- 列名取产品定义中的 `header` 原文。

## 2. 编码与分隔

| 选项 | 取值 | 默认 |
| --- | --- | --- |
| 编码 | UTF-8（不可选） | UTF-8 |
| 分隔符 | `,` `;` `TAB` `\|` | `,` |
| 引号 | 仅在必要时 / 全量加引号 | 仅在必要时 |
| 行结束符 | `CRLF` / `LF` | `CRLF` |
| BOM | 写 / 不写 | 不写 |

引号规则实现见 `ac.domain.export.CsvWriter`：

- 需要引号：值包含分隔符、引号、`CR`、`LF`，或开启“全量加引号”；
- 转义：`"` → `""`；
- 空值输出空字符串（不加引号，除非全量加引号）。

示例（分隔符 `;`、全量引号）：

```csv
"_TestCaseId";"_Remarks";"Trade ID";"Trade Date";"Maturity Date";"Notional";"Notional Currency";"Rate";"Rate";"Direction"
"TC-IRS-0001";"imported";"IRS-1001";"2026-01-05";"2031-01-05";"1500000.00";"USD";"0.0325";"0.0425";"BUY"
```

## 3. 数值与日期

| 类型 | 输出 | 输入（导入时可接受） |
| --- | --- | --- |
| `DECIMAL` | 保留源精度（`1500000.00`），无千分位 | 千分位、空格、全角数字均可 |
| `INTEGER` | 整数文本 | 同上；带小数报错 |
| `DATE` | `yyyy-MM-dd` | `yyyy-MM-dd`、`yyyy/M/d`、`d/M/yyyy`、`M/d/yyyy`、`yyyyMMdd`、`dd MMM yyyy` 等 |
| `BOOLEAN` | `true` / `false` | `Y/N`、`YES/NO`、`T/F`、`1/0` |
| `TEXT` | 原样（去除首尾空白） | 任意 |

公式字段导出**缓存值**（`formulaAsValue=true`），不是公式文本。

## 4. 导入侧约定

- 表头行与交易块起始列可配置（1-based），改动后预览实时刷新。
- 交易块内的未知列进入 `extras`，不会静默丢弃，并给出 `UNMAPPED_TRADE_COLUMN` 警告。
- 交易块之外的列进入 `managed_json`。
- 同名管理列在 `managed_json` 中以 `#2`、`#3` 后缀区分。
- 文件编码自动识别：UTF-8 BOM / UTF-16 BOM → 严格 UTF-8 → GB18030 → windows-1252。
- 分隔符自动识别：在 `,` `;` `TAB` `|` 中按首 5 行一致性挑选。

## 5. 产出的 CSV

外部工具在任务文件夹中产出的 CSV 会被自动登记并解析：

- 跳过 `input/` 目录与隐藏文件；
- 单文件 ≤ 100MB，单次 ≤ 200 个文件；
- 解析使用与主导入相同的对齐算法（自动检测表头行与交易块）；
- 解析失败时 `external_output.status=FAILED`，原因写入 `message`，文件仍可下载。
