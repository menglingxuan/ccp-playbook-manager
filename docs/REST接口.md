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
`INVALID_STATE`(409)、`LIMIT_EXCEEDED`(413)、`UNSUPPORTED_MEDIA_TYPE`(415)、`INTERNAL_ERROR`(500)。

## 上下文与产品

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/context` | 模式、租户、数据目录、版本、数据库产品与版本（`database`）、配置档（`profiles`）、审计开关（`auditEnabled`）、限制、导出默认值、产品列表 |
| GET | `/api/products` | 运行时产品列表（含字段顺序与重名标记） |
| GET | `/api/products/{code}` | 单个产品的运行时字段定义 |

## 产品定义（平台 → 基产品 → 产品）

平台为固定枚举（`MW`、`CFETS`）；**基产品代码不再与平台绑定**，可自由填写（`[A-Z0-9][A-Z0-9_.-]{0,63}`，
同一平台内唯一，数量不限）。`IRS`、`OIS` 等只是出厂种子数据与输入建议。
产品代码全局唯一，字段只能是所属基产品字段的子集。

产品摘要与运行时产品视图都带 `definitionValid`：非严格模式（`strictMatch=false`）下业务字段列名重复时为 `false`，
此时该产品不能导入（预览/导入返回 `409 PRODUCT_DEFINITION_INVALID`，`details.duplicateHeaders` 列出重复列名）。
出厂种子按名称应用该规则：名称含 `Standard` 且声明了重复列名的产品默认开启严格模式，其余产品只保留一份列名并关闭严格模式；
另有 `IRS_DUP_DRAFT`（非标准、保留重复列名、非严格）用于验证无效状态。

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

`roleCode` 只有两项：`BUSINESS`（业务字段，参与交易块映射与必填校验）、`MANAGED`（管理字段，可选或必填，
只有声明过的管理字段才会被识别为管理列）。业务键（Trade ID）由内部 `key_flag` 标记维护，不再作为角色出现。

派生产品继承全部字段，且所有 `BUSINESS` 字段恒为必填；`sortOrder` 不能与同一定义中的其他字段重复（冲突返回 `409`）。

`strictMatch` 为 true 时，导入按**位置**匹配：从「定义起始列号」开始，定义区必须与产品的**全部字段**
（`BUSINESS` + `MANAGED`，按声明顺序）顺序、数量、列名完全一致，否则返回 `STRICT_ORDER_MISMATCH`
（区内出现产品未定义的列时为 `TRADE_BLOCK_NOT_CONTIGUOUS`）并拒绝该 sheet；定义区之外的列一律归为未映射列。
为 false 时按**列名**匹配：业务字段自定义起始列号起按列名匹配、已声明的管理字段按列名在整个表头匹配，
其余列为未映射列；此时产品字段列名重复会使产品定义无效（`definitionValid=false`），预览与导入返回
`409 PRODUCT_DEFINITION_INVALID`。文件侧重复列名只给 `DUPLICATE_SOURCE_HEADER` 警告，仅首个同名列参与映射。

出厂种子：`MW / IRS` 27 个字段（`_TestCaseId`、`_Remarks` + 25 业务字段，交易块首列为必填的 `Report Timestamp`）、
`MW / OIS` 22 个字段（`_TestCaseId`、`_Remarks` + 20 业务字段）、`MW / CCSIRS` 70 个字段（4 个 `MANAGED` 字段
`_TestCaseId`、`_Remarks`、`Scenario Group`、`Priority` + 66 个业务字段，其中前 50 个为必填）。
种子版本升到 `6`：已有库启动时会自动把 `Report Timestamp` 补到 `MW/IRS` 基产品与其派生产品的第 3 位。

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
| POST | `/api/uploads/{id}/preview` | 传 `{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow, dataEndRow, sampleLimit}`，返回布局、列分配、样本行、问题列表 |
| GET | `/api/uploads/{id}/sheets/{index}/raw?rows=20` | 原始单元格（含公式）。未选择产品时向导用它做纯数据预览，不做字段映射 |

`sampleLimit` 只在请求值大于配置 `limits.preview-sample-rows`（默认 20）时生效，上限 500，用于列表滚动分页；
`stats.dataRows` 始终是整张 sheet 的行数，不受 `sampleLimit` 限制。

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
                "lastColumn": 11, "blockContiguous": true, "missingFields": [ ... ] },
    "sampleRows": [ { "row": 4, "values": { "tradeId": "IRS-9001" }, "formulas": { "notional": "=1000000*1" } } ],
    "stats": { "dataRows": 3, "mappedTradeColumns": 8, "managedColumns": 3, "sampleInvalidRows": 0 }
  }
}
```

## 导入与任务

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/imports` | `{uploadId, sheets:[{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow, dataEndRow}], force, includeManagedColumns, includeUnmappedColumns, stopAtEmptyRow, stopAtFirstError}` |

`includeManagedColumns` 默认 `true`：是否把已声明管理列的取值写入 `managed_json`。
`includeUnmappedColumns` 默认 `false`：是否把未映射列的取值写入 `extras`。关闭时对应列的公式也一并丢弃
（公式键与被丢弃的列一一对应）。两个开关只影响落库内容，不影响预览与列归类。

`stopAtEmptyRow` 默认 `false`：遇到空行（整行单元格为空）时停止处理该 sheet，并追加 `IMPORT_STOPPED` 警告；
关闭时跳过空行继续。`stopAtFirstError`（界面「错误行前停止」）默认 `true`：遇到错误行时**在该行之前**停止——
该行及其后的行都不导入，该行的具体错误仍写入 `issues`，另追加 `IMPORT_STOPPED` 警告。错误行包括字段校验
失败的行，以及在开启 `app.import.reject-duplicate-key` 后因 Trade ID 在本 sheet 内重复被拒的行。
关闭时逐行继续，被拒行计入 `invalidRows`。

导入结果（`ImportResult.issues`）只保留需要处理的问题：预览阶段已经展示过的列映射提示
（`UNMAPPED_TRADE_COLUMN`、`DUPLICATE_HEADER_MAPPING`、非严格模式的 `TRADE_BLOCK_NOT_CONTIGUOUS`）不再重复。
`DUPLICATE_IMPORT` 只针对「同一个 upload 的同一个 sheet 被提交两次」；重新上传同一份文件属于新批次，不提示。
`KEY_DUPLICATE` 只在**同一 sheet 内**业务键重复时提示，不再与历史批次比较。

导入会把工作簿（`xlsx` / `xls`）复制一份不可变基线到
`data-root/tenants/{tenant}/batches/{batchId}/source.{ext}`，用于导出时重算公式；CSV 不产生基线。
基线只写一次、读取时校验 `sha256` 与批次记录一致，复制失败时导入结果给 `BASELINE_MISSING` 警告但不阻断导入。
| GET | `/api/tasks` | 分页：`product`（基产品，匹配该任务任一批次产品的基产品）、`q`（源文件名 / 任务 ID / 任务名称 / 基产品）、`from` / `to`（创建日期，`yyyy-MM-dd`，含首尾，按展示时区解释）、`sort=created|name|batches|rows|invalid|duration`（可加 `_asc` / `_desc`，缺省按创建时间倒序）、`page`、`size`；返回项含 `name`（任务名称）、`batchCount`（批次数）、`baseProducts`（关联批次的基产品码）、`sourcePath`（服务端文件位置）、`sourceExists`（文件是否仍在磁盘上） |
| PATCH | `/api/tasks/{id}/name` | 重命名任务：`name` 去空白后非空、≤256 字符、租户内唯一；重名返回 `409 CONFLICT` |
| GET | `/api/tasks/summary` | 各状态任务数 |
| GET | `/api/tasks/{id}` | 任务 + 批次 + 问题明细 |
| GET | `/api/tasks/{id}/batches` | 任务下批次 |
| DELETE | `/api/tasks/{id}` | 软删任务并级联批次与用例 |

任务状态：`PENDING` → `RUNNING` → `SUCCEEDED` / `PARTIAL`（有无效行或某 sheet 失败）/ `FAILED`；删除后为 `DELETED`。

## 批次与用例

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/batches` | `product`、`q`（源文件 / Sheet / 备注 / 标签 / 产品 / 批次 ID）、`kind`、`status`、`tag`、`favorite`、`pinned`、`from` / `to`（创建日期，含首尾）、`sort=created\|name\|rows\|invalid\|product`（可加 `_asc` / `_desc`，缺省创建时间倒序）、`includeDeleted`、`includeExternal`、`page`、`size`；返回项含 `favorite`、`pinned`、`tags` |
| GET | `/api/batches/facets` | 与列表同样的筛选参数；返回 `total` / `favorite` / `pinned` 与 `products` / `kinds` / `statuses` / `tags` 各维度计数（每个维度按其余筛选条件统计） |
| GET | `/api/batches/dock` | `limit`（默认 12，≤30，置顶优先再按时间倒序）、`includeDeleted`，供控制台停靠栏使用 |
| GET | `/api/batches/{id}` | 批次详情（含 `activeCases`） |
| PATCH | `/api/batches/{id}` | `{note}` |
| PATCH | `/api/batches/{id}/meta` | `{favorite?, pinned?, tags?, note?}`，缺省字段保持原值；标签去空白/去重、≤10 个且每个 ≤32 字符、不得含逗号，备注 ≤500 字符 |
| DELETE | `/api/batches/{id}` | 软删批次与用例 |
| GET | `/api/batches/{id}/cases` | `q`、`hasFormula`、`sort=row\|testcase\|key\|formulas\|updated`（可加 `_asc` / `_desc`，缺省原始顺序升序）、`withSummary`（默认 true，返回 `summary.total` / `summary.withFormula`）、`page`、`size` |
| GET | `/api/cases/{id}` | 用例详情（data/managed/formulas/extras/version） |
| GET | `/api/cases/{id}/vertical` | 纵向视图（`identity` / `trade` / `managed` / `extras` 分组） |
| PATCH | `/api/cases/{id}` | `{version, values, managed}`，版本不匹配返回 409 |
| DELETE | `/api/cases/{id}` | 软删 |
| POST | `/api/cases/bulk-delete` | `{ids:[...]}`，≤ 500 |
| GET | `/api/search` | 全局搜索：`q`（自由文本 + 限定词，≥2 字符）、`limit`（每组上限，默认 20，≤50）；限定词 `tag:` `product:` `batch:` `case:` `value:`（别名 `field:`）`note:` `file:` `batchid:` `scope:batch\|case`；返回 `batches` / `cases` 两组命中及 `truncated`，批次级限定词同时收窄用例组 |

## 克隆

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/clones/preview` | `{caseIds, copies, template, note}` → 计划行与冲突 |
| POST | `/api/clones` | 执行，返回 `{batchId, created}`；克隆批次继承原批次的 `taskId`（同时记 `parentBatchId`），因此与原批次一起出现在任务的批次列表中，任务的 `batchCount` 相应 +1 |

模板 token：`{base}` `{seq}` / `{seq:02}` `{uuid}` `{date:yyyyMMdd}` `{batch}`。

## 导出

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/exports/precheck` | 返回 `valid`、`rowCount`、`headers`、`sampleRow`、`issues` |
| POST | `/api/exports` | 生成 CSV 并**存档为记录**（返回 `exportId`、`fileName`、`rowCount`、`size`、`warnings`），前端不再自动下载 |
| GET | `/api/exports` | 导出记录：`q`（文件名 / 产品 / 批次 ID / 导出 ID）、`product`、`batchId`、`from` / `to`、`sort=created\|rows\|size`（可加 `_asc` / `_desc`）、`page`、`size`；返回项含 `options`、`fileExists`、`downloadUrl` |
| GET | `/api/exports/{id}/download` | 下载存档文件（`Content-Disposition` 带 UTF-8 文件名） |
| DELETE | `/api/exports/{id}` | 删除导出记录并删除存档文件 |

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
  },
  "recalculateFormulas": true
}
```

`recalculateFormulas` 缺省为 `true`：导出前用批次的不可变基线工作簿重算公式，公式字段输出重算后的值
（当前用例值先按 Excel 原生类型写回对应单元格，未导入的行与辅助列沿用基线原值）。求值失败的单元格保留
用例存储值，并在 `ExportResult.warnings` 中给出 `FORMULA_RECALC_WARNING` / `FORMULA_RECALC_INCOMPLETE` /
`FORMULA_RECALC_SKIPPED` 说明；预检接口返回的 `sampleRow` 同样是重算后的值。

## 产出

外部「批量导入工具」派发链路已移除（`/api/external-jobs/*` 不再提供），产出浏览端点保留用于查看历史产出：

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/outputs` | 产出浏览（可 `jobId` 过滤） |
| GET | `/api/outputs/{id}` | 产出详情 |
| GET | `/api/outputs/{id}/cases` | 产出内用例 |
| DELETE | `/api/outputs/{id}` | 删除产出及其批次 |

## 其他

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/resources/stats` | 当前租户的资源统计：文件区域（文件数 / 占用 / 最近变更）、数据库产品与版本、各业务表行数、运行时（堆/非堆、线程、运行时长、处理器、系统负载）与磁盘空间 |
| GET | `/api/audit` | 审计记录：`module`（`PRODUCT_DEFINITION` / `META_DICTIONARY`，历史记录还可能是 `TASK` / `BATCH` / `CASE` / `EXPORT` / `OUTPUT` / `EXTERNAL_JOB` / `OTHER`）、`entityType`、`action`、`q`、`from`、`to`、`page`、`size`；`app.audit.enabled=false` 时返回 404 |
| GET | `/api/audit/facets` | 审计面板的维度计数：`modules` / `entities` / `actions` / `total`，每个维度忽略自身筛选；关闭审计时返回 404 |
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
| `/batches` | 批次列表（导入 + 克隆） |
| `/batches/{id}` | 批次详情 |
| `/outputs`、`/outputs/{id}` | 产出浏览 / 详情 |
| `/products` | 产品定义（平台 → 基产品 → 产品） |
| `/meta` | 字段元信息 |
| `/resources` | 资源统计 |
| `/audit` | 审计日志（`app.audit.enabled=false` 时 404） |
| `/about` | 关于 |
