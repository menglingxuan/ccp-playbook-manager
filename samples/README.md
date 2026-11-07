# 演示数据

由 `gradle :otc-clear-web:generateSamples` 生成（源码：`otc-clear-web/src/test/java/ac/tools/SampleWorkbookGenerator.java`）。
生成结果由 `ac.web.SampleDataTest` 校验，列数、行数或结构变化会导致测试失败。

| 文件 | 产品 | 结构 | 用途 |
| --- | --- | --- | --- |
| `irs_workbook.xlsx` | IRS / OIS | 4 个 sheet | 多 sheet 解析、表头行偏移、公式列、宽表 |
| `irs_cases.csv` | IRS | 11 列 × 3 行 | 含 `_TestCaseId` / `_Remarks` 管理列与一个未映射列 `Extra Ref` |
| `ois_cases.csv` | OIS | 11 列 × 2 行 | 无管理列的最小样例 |
| `ccsirs_wide.csv` | CCSIRS | 30 列 × 100 行 | 宽表 CSV，左右各 2 个管理列 |

## irs_workbook.xlsx 的 4 个 sheet

| # | Sheet | 结构 | 说明 |
| --- | --- | --- | --- |
| 1 | `说明` | 4 行文本 | 文档页，没有交易块，导入时会失败并演示问题定位 |
| 2 | `IRS` | 11 列 × 3 行 | 表头在第 3 行、交易字段从第 3 列开始；`Notional` 为公式列 |
| 3 | `OIS` | 11 列 × 1 行 | 表头在第 1 行，无管理列 |
| 4 | `IRS_Wide` | **70 列 × 200 行** | 宽表：左侧 4 个管理列 + 24 个交易列 + 右侧 42 个管理列 |

### IRS_Wide 列结构（70 列）

```
列 1-4    管理列      _TestCaseId、_Remarks、Scenario Group、Priority
列 5-28   交易列      IRS 全部 24 个字段（schema 顺序；固定/浮动利率两列同名 "Rate"）
列 29-70  管理列      Booking System … Audit Trail Ref（42 个业务列）
```

- 表头在第 2 行（第 1 行是标题），数据 200 行，全部通过校验。
- `Notional`（交易列）与 `Notional Check`（管理列）为公式列，导入后 `formula_json` 同时记录
  `fields.notional` 与 `managed.Notional Check`，纵向视图显示 `ƒ` 徽标。
- 空值的管理列（`Cap`、`Floor`、`Barrier`、`Notes 3`，以及 7 行一次的 `Compression ID`）
  不会写入 `managed_json`，这是预期行为。

### ccsirs_wide.csv 列结构（30 列）

```
列 1-2    管理列      _TestCaseId、_Remarks
列 3-26   交易列      CCSIRS 全部 24 个字段（两个 Rate、两个 Notional）
列 27-30  管理列      _Reviewer、_Priority、Env、Scenario
```

- 100 行数据全部通过校验；两个 Notional 均为 2 位小数（导出仍为 `1620000.00`）。

## 走一遍完整流程

1. 启动服务（`run-single.cmd` 或 `java -jar ...`）。
2. `/import` 上传 `irs_workbook.xlsx`：
   - 向导会自动跳到第一个可用 sheet（`IRS`），可手动切到 `IRS_Wide` 查看 70 列预览；
   - `说明` sheet 找不到交易块，导入时计入 `PARTIAL`，用于演示问题定位；
   - `IRS` sheet 自动识别表头第 3 行、交易列从第 3 列开始，`Notional` 显示公式徽标；
   - 点「提交全部 Sheet」可一次生成 3 个批次（`说明` 记失败）。
3. 上传 `ccsirs_wide.csv`：验证第 3 个产品与 100 行宽表，导入后批次页可分页浏览。
4. 批次页：纵向浏览、编辑、克隆（如 200 行批次克隆 2 条 × 2 份）、导出 CSV。
5. 外部任务：勾选用例 → 「用批量导入工具导入」（未启用外部服务时会提示，可在任务文件夹手工放一个 CSV 再「扫描产出」）。
6. `/outputs` 查看产出导入结果。
