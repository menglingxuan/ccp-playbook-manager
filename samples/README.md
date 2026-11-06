# 演示数据

由 `gradle :otc-clear-web:generateSamples` 生成（源码：`otc-clear-web/src/test/java/ac/tools/SampleWorkbookGenerator.java`）。

| 文件 | 用途 |
| --- | --- |
| `irs_workbook.xlsx` | 3 个 sheet：`说明`（文档）、`IRS`（表头在第 3 行、交易字段从第 3 列开始、Notional 为公式列）、`OIS`（表头在第 1 行） |
| `irs_cases.csv` | 单 sheet CSV，含 `_TestCaseId` / `_Remarks` 管理列与一个未映射列 `Extra Ref` |
| `ois_cases.csv` | OIS 产品 CSV，无管理列 |

## 走一遍完整流程

1. 启动服务（`run-single.cmd` 或 `java -jar ...`）。
2. `/import` 上传 `irs_workbook.xlsx`：
   - `说明` sheet 会因找不到交易块而失败（任务状态为 `PARTIAL`），用于演示问题定位；
   - `IRS` sheet 自动识别表头第 3 行、交易列从第 3 列开始；
   - 预览中 Notional 显示公式徽标，值取缓存值。
3. 打开批次页：纵向浏览、编辑、克隆 3 条 × 2 份、导出 CSV。
4. 外部任务：勾选用例 → 「用批量导入工具导入」（未启用外部服务时会提示，可在任务文件夹手工放一个 CSV 再「扫描产出」）。
5. `/outputs` 查看产出导入结果。
