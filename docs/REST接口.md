# REST 接口

统一前缀 `/api`。错误使用 RFC 7807 `application/problem+json`，附加 `code` 与 `details` 字段。

```json
{
  "type": "about:blank",
  "title": "NOT_FOUND",
  "status": 404,
  "detail": "Case xxx does not exist",
  "code": "NOT_FOUND",
  "details": {}
}
```

错误码：`INVALID_REQUEST`(400)、`NOT_FOUND`(404)、`CONFLICT`(409)、`DUPLICATE_IMPORT`(409)、
`INVALID_STATE`(409)、`LIMIT_EXCEEDED`(413)、`UNSUPPORTED_MEDIA_TYPE`(415)、
`EXTERNAL_UNAVAILABLE`(502)、`INTERNAL_ERROR`(500)。

## 上下文与产品

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/context` | 模式、租户、数据目录、版本、限制、导出默认值、产品列表 |
| GET | `/api/products` | 产品列表（含字段顺序与重名标记） |
| GET | `/api/products/{code}` | 单个产品定义 |

## 上传与向导

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/uploads` | multipart `file`，返回 `uploadId` 与 sheet 画像 |
| GET | `/api/uploads/{id}` | 当前画像 |
| POST | `/api/uploads/{id}/preview` | 传 `{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow}`，返回布局、列分配、样本行、问题列表 |
| GET | `/api/uploads/{id}/sheets/{index}/raw?rows=20` | 原始单元格（含公式） |

`preview` 响应要点：

```json
{
  "upload": { "uploadId": "...", "fileName": "cases.xlsx", "sheets": [ ... ] },
  "sheetIndex": 1,
  "productCode": "IRS",
  "preview": {
    "valid": true,
    "issues": [ { "level": "WARN", "code": "DUPLICATE_HEADER_MAPPING", "message": "..." } ],
    "layout": { "headerRow": 3, "dataStartRow": 4, "tradeStartColumn": 3, "tradeEndColumn": 10,
                "lastColumn": 11, "ambiguous": false, "missingFields": [ ... ] },
    "sampleRows": [ { "row": 4, "values": { "tradeId": "IRS-9001" }, "formulas": { "notional": "=1000000*1" } } ],
    "stats": { "dataRows": 3, "mappedTradeColumns": 8, "managedColumns": 3, "sampleInvalidRows": 0 }
  }
}
```

## 导入与任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/imports` | `{uploadId, sheets:[{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow}], force}` |
| GET | `/api/tasks` | 分页：`status`、`kind`、`product`、`q`、`sort=created|rows|invalid|duration`、`page`、`size` |
| GET | `/api/tasks/summary` | 各状态任务数 |
| GET | `/api/tasks/{id}` | 任务 + 批次 + 问题明细 |
| GET | `/api/tasks/{id}/batches` | 任务下批次 |
| DELETE | `/api/tasks/{id}` | 软删任务并级联批次与用例 |

任务状态：`PENDING` → `RUNNING` → `SUCCEEDED` / `PARTIAL`（有无效行或某 sheet 失败）/ `FAILED`；删除后为 `DELETED`。

## 批次与用例

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/batches` | `product`、`q`、`includeExternal`、`page`、`size` |
| GET | `/api/batches/{id}` | 批次详情（含 `activeCases`） |
| PATCH | `/api/batches/{id}` | `{note}` |
| DELETE | `/api/batches/{id}` | 软删批次与用例 |
| GET | `/api/batches/{id}/cases` | `q`、`hasFormula`、`page`、`size` |
| GET | `/api/cases/{id}` | 用例详情（data/managed/formulas/extras/version） |
| GET | `/api/cases/{id}/vertical` | 纵向视图（`identity` / `trade` / `managed` / `extras` 分组） |
| PATCH | `/api/cases/{id}` | `{version, values, managed}`，版本不匹配返回 409 |
| DELETE | `/api/cases/{id}` | 软删 |
| POST | `/api/cases/bulk-delete` | `{ids:[...]}`，≤ 500 |

## 克隆

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/clones/preview` | `{caseIds, copies, template, note}` → 计划行与冲突 |
| POST | `/api/clones` | 执行，返回 `{batchId, created}` |

模板 token：`{base}` `{seq}` / `{seq:02}` `{uuid}` `{date:yyyyMMdd}` `{batch}`。

## 导出

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/exports/precheck` | 返回 `valid`、`rowCount`、`headers`、`sampleRow`、`issues` |
| POST | `/api/exports` | 生成文件并记录 `export_job` |
| GET | `/api/exports` | 导出历史 |
| GET | `/api/exports/{id}/download` | 下载（`Content-Disposition` 带 UTF-8 文件名） |

请求体：

```json
{
  "batchId": "可选，与 caseIds 二选一",
  "caseIds": ["可选"],
  "options": {
    "delimiter": ";",
    "quoteAll": false,
    "includeHeader": true,
    "lineEnding": "\r\n",
    "bom": false,
    "managedColumns": "KNOWN",
    "formulaAsValue": true
  }
}
```

## 外部任务与产出

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/external-jobs/precheck` | `{caseIds}` → `valid` / `serviceEnabled` / `issues` |
| POST | `/api/external-jobs` | 创建并派发 |
| GET | `/api/external-jobs` | `status`、`product`、`q`、`page`、`size` |
| GET | `/api/external-jobs/{id}` | 详情 + 产出列表 |
| POST | `/api/external-jobs/{id}/refresh` | 主动拉取状态 |
| POST | `/api/external-jobs/{id}/stop` | 停止 |
| POST | `/api/external-jobs/{id}/callback` | 外部回调（token 校验） |
| POST | `/api/external-jobs/{id}/scan` | 扫描产出并解析入库 |
| GET | `/api/external-jobs/{id}/outputs` | 产出列表 |
| GET | `/api/external-jobs/{jobId}/files/{outputId}/download` | 下载产出文件 |
| GET | `/api/outputs` | 产出浏览（可 `jobId` 过滤） |
| GET | `/api/outputs/{id}` | 产出详情 |
| GET | `/api/outputs/{id}/cases` | 产出内用例 |
| DELETE | `/api/outputs/{id}` | 删除产出及其批次 |

## 其他

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/audit` | `entityType`、`action`、`page`、`size` |
| GET | `/api/settings` | 租户导出默认值 |
| PUT | `/api/settings` | 保存导出默认值 |
| GET | `/actuator/health` | 健康检查 |

## 页面

| 路径 | 页面 |
| --- | --- |
| `/` | 任务面板 |
| `/import` | 导入向导 |
| `/tasks/{id}` | 任务详情 |
| `/batches/{id}` | 批次详情 |
| `/outputs`、`/outputs/{id}` | 产出浏览 / 详情 |
| `/external-jobs`、`/external-jobs/{id}` | 外部任务 / 详情 |
| `/products`、`/about` | 产品定义 / 关于 |
