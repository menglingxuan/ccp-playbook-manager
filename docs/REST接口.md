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
| GET | `/api/products` | 运行时产品列表（含字段顺序与重名标记） |
| GET | `/api/products/{code}` | 单个产品的运行时字段定义 |

## 产品定义（平台 → 基产品 → 产品）

平台为固定枚举（`MW`、`CFETS`）；**基产品代码不再与平台绑定**，可自由填写（`[A-Z0-9][A-Z0-9_.-]{0,63}`，
同一平台内唯一，数量不限）。`IRS`、`OIS` 等只是出厂种子数据与输入建议。
产品代码全局唯一，字段只能是所属基产品字段的子集。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/catalog` | 平台及其基产品（含字段数、产品数、`schemaHash`） |
| GET | `/api/catalog/search?q=&platform=` | 全局搜索：基产品、产品、基产品字段、产品字段（列名/别名/标签/备注/名称/代码）；`platform` 可选，限定平台 |
| GET | `/api/catalog/base-codes/suggestions` | 内置基产品代码建议（仅用于新建时的提示，代码可自由填写） |
| POST | `/api/catalog/platforms/{platform}/base-products` | `{code, name, description}` |
| GET | `/api/catalog/base-products/{id}` | 基产品 + 字段 + 衍生产品 |
| PATCH | `/api/catalog/base-products/{id}` | `{name, description, sortOrder}` |
| DELETE | `/api/catalog/base-products/{id}` | 存在产品时返回 409 |
| POST | `/api/catalog/base-products/{id}/fields` | 字段定义，见下 |
| POST | `/api/catalog/base-products/{id}/fields/from-headers` | `{headers:[...]}`：按 CSV 首行批量建字段（文本 / 业务字段 / 必填），已存在的列名跳过 |
| PATCH | `/api/catalog/base-products/{id}/fields/{fieldId}` | 同上（全量覆盖） |
| DELETE | `/api/catalog/base-products/{id}/fields/{fieldId}` | 级联删除产品中的同名副本，返回 `removedProductFields` |
| POST | `/api/catalog/base-products/{id}/fields/reorder` | `{fieldIds:[...]}` |
| POST | `/api/catalog/base-products/{id}/products` | `{code, name, description, excludedFieldKeys}`；`excludedFieldKeys` 省略即继承全部字段 |
| GET | `/api/catalog/products/{id}` | 产品 + 字段（含是否继承必填） |
| PATCH | `/api/catalog/products/{id}` | `{name, description, strictMatch}`；字段为 `null` 时保持原值 |
| DELETE | `/api/catalog/products/{id}` | 被批次或用例引用时返回 409 |
| PATCH | `/api/catalog/products/{id}/fields/{fieldId}` | `{required, sortOrder, tags, remark}`；业务字段的 `required` 恒为 true |
| DELETE | `/api/catalog/products/{id}/fields/{fieldId}` | 从产品中移除该字段 |
| POST | `/api/catalog/products/{id}/fields/reorder` | `{fieldIds:[...]}`，产品独立序号 1..N |
| POST | `/api/catalog/products/{id}/sync` | `{addMissing}`，同步基产品字段变化 |

字段定义请求体：

```json
{
  "fieldKey": "可选，留空由列名推导",
  "header": "Rate",
  "displayName": "内部显示名（不在列表展示）",
  "aliases": ["Floating Rate"],
  "valueTypeCode": "DECIMAL",
  "roleCode": "BUSINESS",
  "tags": ["CLEARING"],
  "required": true,
  "remark": "备注",
  "sortOrder": 7
}
```

`valueTypeCode` 与 `roleCode` 必须是 `VALUE_TYPE` / `FIELD_ROLE` 中已启用的枚举值；
`tags` 取自 `FIELD_TAG`。值类型可以是枚举引用的类型（如 `CONV_METHOD`），导入时按被引用集合的取值校验。

`roleCode` 只有两项：`BUSINESS`（业务字段，参与交易块对齐与必填校验）、`MANAGED`（管理字段，
允许落在交易块之外，可选或必填）。业务键（Trade ID）由内部 `key_flag` 标记维护，不再作为角色出现。

派生产品继承全部字段，且所有 `BUSINESS` 字段恒为必填；`sortOrder` 不能与同一定义中的其他字段重复（冲突返回 `409`）。

`strictMatch` 为 true 时，导入的交易块必须与产品业务字段顺序、数量完全一致，否则返回 `STRICT_ORDER_MISMATCH` 并拒绝该 sheet。

`MW / CCSIRS` 出厂预置 70 个字段：4 个 `MANAGED` 字段（`_TestCaseId`、`_Remarks`、`Scenario Group`、
`Priority`）加 66 个业务字段，其中前 50 个为必填。

## 字段元信息

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/meta/sets` | 集合及其枚举值 |
| GET | `/api/meta/options` | 按集合分组的启用枚举值（字段编辑器使用） |
| POST | `/api/meta/sets` | `{code, name, description, sortOrder?}`；`sortOrder` 省略时追加到末尾 |
| PATCH | `/api/meta/sets/{code}` | 更新集合；`sortOrder` 为 `null` 时保持原值 |
| DELETE | `/api/meta/sets/{code}` | 内置集合或被引用时返回 400 / 409 |
| POST | `/api/meta/sets/{code}/items` | `{code, label, refSetCode, sortOrder, enabled, remark}`；`sortOrder`/`enabled` 省略时追加、默认启用；序号重复返回 409 |
| PATCH | `/api/meta/sets/{code}/items/{itemCode}` | 更新枚举值；`sortOrder` / `enabled` 为 `null` 时保持原值，序号重复返回 409 |
| DELETE | `/api/meta/sets/{code}/items/{itemCode}` | 被字段定义引用时返回 409 |

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
| GET | `/api/i18n` | 当前语言的文案字典 `{locale, messages}` |
| GET | `/actuator/health` | 健康检查 |

## 多语言

支持三种语言：`zh-CN`（简体中文，默认）、`zh-HK`（简体中文（香港））、`en`（英语）。

- 语言保存在 `lang` Cookie（一年有效），可用 `?lang=en` 临时切换；
- 服务端用 `messages*.properties` 渲染页面标题与静态文案，前端用 `/api/i18n` 取同一套字典；
- 顶栏语言下拉切换后会重新加载页面；
- 目前覆盖全站外壳（顶栏、导航、通用状态徽标）与「产品定义」「字段元信息」两个页面，其余页面保留中文，可按同一套 key 逐步迁移。

## 页面

| 路径 | 页面 |
| --- | --- |
| `/` | 任务面板 |
| `/import` | 导入向导 |
| `/tasks/{id}` | 任务详情 |
| `/batches/{id}` | 批次详情 |
| `/outputs`、`/outputs/{id}` | 产出浏览 / 详情 |
| `/external-jobs`、`/external-jobs/{id}` | 外部任务 / 详情 |
| `/products` | 产品定义（平台 → 基产品 → 产品） |
| `/meta` | 字段元信息 |
| `/about` | 关于 |
