# 演示数据

由 `gradle :otc-clear-web:generateSamples` 生成（源码：`otc-clear-web/src/test/java/ac/tools/SampleWorkbookGenerator.java`）。
生成结果由 `ac.web.SampleDataTest` 校验，列数、行数或结构变化会导致测试失败。

| 文件 | 产品 | 结构 | 用途 |
| --- | --- | --- | --- |
| `irs_workbook.xlsx` | IRS / OIS | 5 个 sheet | 多 sheet 解析、表头行偏移、公式列、宽表、含 `Report Timestamp` 的完整定义 |
| `irs_cases.csv` | IRS | 28 列 × 3 行 | 含 `_TestCaseId` / `_Remarks` 管理列与必填业务列 `Report Timestamp`，覆盖 IRS 全部 25 个业务列，另加一个未映射列 `Extra Ref` |
| `ois_cases.csv` | OIS | 20 列 × 2 行 | 无管理列，覆盖 OIS 全部 20 个业务列 |
| `ccsirs_wide.csv` | CCSIRS | 70 列 × 100 行 | 宽表 CSV，左侧 4 个管理列 + 66 个交易列（覆盖 CCSIRS 全字段） |

## irs_workbook.xlsx 的 5 个 sheet

| # | Sheet | 结构 | 说明 |
| --- | --- | --- | --- |
| 1 | `说明` | 4 行文本 | 文档页，没有交易块，导入时会失败并演示问题定位 |
| 2 | `IRS.Standard` | 28 列 × 3 行 | 表头在第 1 行，映射 MW/IRS 全部 27 个字段；`Report Timestamp` 为必填业务列（第 3 列），`Extra Ref` 未映射 |
| 3 | `IRS` | 11 列 × 3 行 | 表头在第 3 行、交易字段从第 3 列开始；`Notional` 为公式列 |
| 4 | `OIS` | 11 列 × 1 行 | 表头在第 1 行，无管理列 |
| 5 | `IRS_Wide` | **70 列 × 200 行** | 宽表：左侧 4 个管理列 + 24 个交易列 + 右侧 42 个管理列 |

### IRS.Standard 列结构（28 列）

```
列 1-2    管理列      _TestCaseId、_Remarks
列 3      业务列      Report Timestamp（必填；Excel 时间单元格按原样保存为文本，如 2026-11-09T20:34:00.970）
列 4-27   业务列      IRS 全部 24 个交易字段（schema 顺序；固定/浮动利率两列同名 "Rate"）
列 28     未映射列    Extra Ref
```

- 严格模式下定义区为第 1-27 列，与产品定义逐位一致；`Extra Ref` 区外，归入未映射列。

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

### ccsirs_wide.csv 列结构（70 列）

```
列 1-4    管理列      _TestCaseId、_Remarks、Scenario Group、Priority
列 5-70   交易列      CCSIRS 全部 66 个字段（schema 顺序；前 50 列为必填列）
```

- 100 行数据全部通过校验；两个 Notional 均为 2 位小数（导出仍为 `1620000.00`）。
- 派生产品的业务字段恒为必填，因此样例覆盖全部业务列且不留空值；仅管理字段保持可选。

## 走一遍完整流程

1. 启动服务（`run-single.cmd` 或 `java -jar ...`）。
2. `/import` 上传 `irs_workbook.xlsx`：
   - 向导会自动跳到第一个可用 sheet（`说明` 无交易块，接着是 `IRS.Standard`），可手动切到 `IRS_Wide` 查看 70 列预览；
   - `说明` sheet 找不到交易块，导入时计入 `PARTIAL`，用于演示问题定位；
   - `IRS.Standard` 用 `IRS` 产品严格模式预览：27 个字段全部映射、`Extra Ref` 未映射；
   - `IRS` sheet 自动识别表头第 3 行、交易列从第 3 列开始，`Notional` 显示公式徽标；
3. 上传 `ccsirs_wide.csv`：验证第 3 个产品与 100 行宽表，导入后批次页可分页浏览。
4. 批次页：纵向浏览、编辑、克隆（如 200 行批次克隆 2 条 × 2 份）、导出 CSV。
5. 外部任务：勾选用例 → 「用批量导入工具导入」（未启用外部服务时会提示，可在任务文件夹手工放一个 CSV 再「扫描产出」）。
6. `/outputs` 查看产出导入结果。
