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
| GET | `/api/context` | 模式、租户、租户标识开关（`showTenant`，来自 `app.ui.show-tenant`，默认 `false`）、数据目录、版本、数据库产品与版本（`database`）、配置档（`profiles`）、审计开关（`auditEnabled`）、限制、界面偏好（`ui`：`issueInlineLimit` / `issuePreviewRows`）、导出默认值、产品列表 |
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
| POST | `/api/catalog/base-products/{id}/fields/from-csv` | multipart `file`（CSV）：读首行列名批量建字段（`TEXT` / `BUSINESS` / 必填，序号从当前最大值续排，重复列名跳过），返回 `{created:[fieldKey...], headers:n}`；空文件或无列名返回 400 |
| PATCH | `/api/catalog/base-products/{id}/fields/{fieldId}` | 同上（全量覆盖） |
| DELETE | `/api/catalog/base-products/{id}/fields/{fieldId}` | 级联删除产品中的同名副本，返回 `removedProductFields` |
| POST | `/api/catalog/base-products/{id}/fields/reorder` | `{fieldIds:[...]}` |
| POST | `/api/catalog/base-products/{id}/products` | `{code, name, description, excludedFieldKeys}`；`excludedFieldKeys` 省略即继承全部字段 |
| GET | `/api/catalog/products/{id}` | 产品 + 字段（含是否继承必填） |
| PATCH | `/api/catalog/products/{id}` | `{name, description, strictMatch}`；字段为 `null` 时保持原值 |
| DELETE | `/api/catalog/products/{id}` | 被有效批次或用例引用时返回 409（软删的批次与用例不计入占用） |
| PATCH | `/api/catalog/products/{id}/fields/{fieldId}` | `{required, sortOrder, tags, remark, validationExpr}`；`required=false` 只在基产品同名字段也是选填时允许（否则 `409`），`required=true` 始终允许；`validationExpr` 为 `null` 时保持原值、空串表示恢复继承基产品的表达式 |
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

派生产品继承全部字段，`required` 以基产品为准：基产品必填的字段在派生产品中只能是必填（改为选填返回 `409`），
基产品选填的字段在派生产品中可保持选填，也可升级为必填；基产品把选填改为必填时，若任一派生产品的同名字段仍为选填，
`PATCH /api/catalog/base-products/{id}/fields/{fieldId}` 返回 `409` 并在消息中列出这些产品，基产品把必填改为选填不影响派生产品当前设置。
`sortOrder` 不能与同一定义中的其他字段重复（冲突返回 `409`）。

`strictMatch` 为 true 时，导入按**位置**匹配：从「定义起始列号」开始，定义区必须与产品的**全部字段**
（`BUSINESS` + `MANAGED`，按声明顺序）顺序、数量、列名完全一致，否则返回 `STRICT_ORDER_MISMATCH`
（区内出现产品未定义的列时为 `TRADE_BLOCK_NOT_CONTIGUOUS`）并拒绝该 sheet；定义区之外的列一律归为未映射列。
为 false 时按**列名**匹配：业务字段自定义起始列号起按列名匹配、已声明的管理字段按列名在整个表头匹配，
其余列为未映射列；此时产品字段列名重复会使产品定义无效（`definitionValid=false`），预览与导入返回
`409 PRODUCT_DEFINITION_INVALID`。文件侧重复列名只给 `DUPLICATE_SOURCE_HEADER` 警告，仅首个同名列参与映射。

出厂种子：`MW / IRS` 27 个字段（`_TestCaseId`、`_Remarks` + 25 业务字段，交易块首列为必填的 `Report Timestamp`）、
`MW / OIS` 22 个字段（`_TestCaseId`、`_Remarks` + 20 业务字段）、`MW / CCSIRS` 70 个字段（4 个 `MANAGED` 字段
`_TestCaseId`、`_Remarks`、`Scenario Group`、`Priority` + 66 个业务字段，其中前 50 个为必填）。
种子版本 `6`：已有库启动时会自动把 `Report Timestamp` 补到 `MW/IRS` 基产品与其派生产品的第 3 位。
种子版本 `7`：`MW/IRS`、`MW/OIS` 的示例业务字段全部标记为必填（对齐上一版「派生产品业务字段恒必填」的实际行为），
已有库启动时同步基产品与其派生产品副本的 `required_flag` 并重算 `schema_hash`；`MW/CCSIRS` 的 50 必填 / 16 选填模板保持不变。

## 元数据

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/meta/sets` | 集合及其枚举值；集合含 `type`（`VALUE` / `FORMULA` / `TAG` / `VALIDATION`），枚举值含 `content` / `ruleType` / `severity` |
| GET | `/api/meta/tags` | 所有 `TAG` 集合的启用枚举值去重后的标签文本（`content` 优先，缺省用取值名称），供批次标签输入提示；忽略集合名 |
| GET | `/api/meta/options` | 按集合分组的启用枚举值（字段编辑器使用） |
| GET | `/api/meta/rules` | 所有 `VALIDATION` 集合的启用规则（`code` / `label` / `content` / `ruleType` / `severity`），字段编辑器用作规则码提示 |
| POST | `/api/meta/rules/test` | `{expression, text}` → `{valid, matched, message, problems}`；编译并求值字段表达式（`AND` / `OR` / `NOT` / 括号，规则码） |
| POST | `/api/meta/rules/match` | `{ruleType, expression, text}` → `{valid, matched, message}`；编译单条规则并与文本做全匹配，供规则编辑器即时测试。表达式长度上限 300 字符、样本文本上限 4000 字符（超出时 `valid=true, matched=false` 并在 `message` 说明），且拒绝「在已含 `*`/`+`/`{m,}` 的分组上再加重词」的写法（如 `(.*a){14}`、`(a+)+`），这类嵌套量词会指数级回溯 |
| GET | `/api/meta/sets/{code}/items` | 单个集合的枚举值（批量更新对话框按集合取新值时使用）；返回值带 `readonly`，为 `true` 表示业务处理依赖该取值，界面不提供编辑 / 删除 |
| POST | `/api/meta/sets` | `{code, name, description, sortOrder?, type?}`；`type` 缺省为 `VALUE`，`sortOrder` 省略时追加到末尾 |
| PATCH | `/api/meta/sets/{code}` | 更新集合（含 `type`）；`sortOrder` / `type` 为 `null` 时保持原值 |
| DELETE | `/api/meta/sets/{code}` | **内置集合**（仅 `VALUE_TYPE` / `FIELD_ROLE`）返回 400；被其他集合的取值通过「引用集合」指向、集合内任一取值被字段定义使用、或（`VALIDATION` 集合）规则被字段表达式引用时返回 409。`FIELD_TAG` / `CONV_METHOD` 是种子写入的示例字典，已标记为非内置，可改名或删除 |
| POST | `/api/meta/sets/{code}/items` | `{code, label, refSetCode, sortOrder, enabled, remark, content, ruleType}`；`content` 为值文本（`VALUE`）、公式（`FORMULA`，须以 `=` 开头）或校验表达式（`VALIDATION`，`ruleType` 为 `REGEX` / `WILDCARD`，编译失败返回 400；`severity` 为 `WARN`（默认）/ `ERROR`，其他值返回 400，非 `VALIDATION` 集合恒为 `WARN`）；`sortOrder`/`enabled` 省略时追加、默认启用；序号重复或规则码已存在（跨 `VALIDATION` 集合唯一）返回 409 |
| PATCH | `/api/meta/sets/{code}/items/{itemCode}` | 更新枚举值（含 `content` / `ruleType` / `severity`）；`sortOrder` / `enabled` 为 `null` 时保持原值，序号重复返回 409；`VALUE_TYPE` 的必须取值（`TEXT`/`INTEGER`/`DECIMAL`/`DATE`/`TIME`/`DATETIME`/`BOOLEAN`）与 `FIELD_ROLE` 的 `BUSINESS`/`MANAGED` 不可编辑，返回 409 |
| DELETE | `/api/meta/sets/{code}/items/{itemCode}` | 被字段定义引用时返回 409（`FIELD_TAG` 的取值按「引用该标签的字段数」计数）；上面列出的必须取值同样返回 409 |

## 定义备份与还原

两个范围：`scope=PRODUCT`（平台 / 基产品 / 基产品字段 / 产品 / 产品字段）与 `scope=META`（集合与枚举值）。

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/definitions/export?scope=PRODUCT\|META&format=xlsx\|sql` | 以附件下载整份定义。`xlsx` 每个表一个 sheet（首行为列名），是可再导入的格式；`sql` 为可重放的备份脚本（注释头 + 事务 + 限当前租户的 `DELETE` + 保留 ID 的 `INSERT`，平台用 `MERGE`）。未知 scope / format 返回 400 |
| POST | `/api/definitions/import/preview?scope=…` | multipart 上传 `xlsx`，只做预检不写库：返回 `planId` / `scope` / `fileName` / `createdAt` / `counts`（`ADD` / `CONFLICT` / `UNCHANGED` / `INVALID` / `TOTAL`）/ `rows`（每行 `table` / `key` / `status` / `messages`）/ `notes`。缺少 sheet、缺少键列、非工作簿文件返回 400；预检结果在服务端保留 30 分钟 |
| POST | `/api/definitions/import/{planId}` | `{mode}`：`MERGE_OVERWRITE`（默认，写入新增并覆盖冲突）、`MERGE_SKIP`（只写新增，冲突保留）、`FULL_RESTORE`（先清空该范围，再写入文件内容；`META` 范围保留必须取值）。返回 `created` / `updated` / `skipped` / `invalid` / `deleted` / `notes`；未知或过期计划返回 404，未知模式返回 400 |
| DELETE | `/api/definitions/import/{planId}` | 放弃预检结果 |
| GET | `/api/definitions/import/{planId}` | 读取仍有效的预检结果 |

预检分类规则：`ADD` = 业务键不存在；`CONFLICT` = 同键已存在且可写字段不同（平台 / 基产品 / 字段 / 产品 / 集合 / 枚举值各自比较各自可写列，产品改挂基产品视为无效）；`UNCHANGED` = 完全一致；`INVALID` = 缺必填、引用不存在（平台 / 基产品 / 产品 / 集合 / 值类型 / 角色）、文件内键重复、产品字段在基产品中无对应字段、校验表达式编译失败、规则码跨集合重复、必须取值被修改。无效行不写入，其余行照常导入。

## 上传与向导

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| POST | `/api/uploads` | multipart `file`，返回 `uploadId` 与 sheet 画像 |
| GET | `/api/uploads/{id}` | 当前画像 |
| POST | `/api/uploads/{id}/preview` | 传 `{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow, dataEndRow, sampleLimit, sampleOffset}`，返回布局、列分配、当前页行、问题列表 |
| POST | `/api/uploads/{id}/preview/rows` | 同上参数，只返回**一页**数据行：`{sheetIndex, productCode, offset, limit, totalRows, rows, issues}`。向导翻页条用它浏览整份文件，`sampleOffset` 为页首行的偏移，`totalRows` 是表头以下的数据行数，越界时 `rows` 为空 |
| GET | `/api/uploads/{id}/sheets/{index}/raw?rows=20&offset=0` | 原始单元格（含公式）。未选择产品时向导用它做纯数据预览，不做字段映射；`offset` 为按 sheet 行跳过的行数，响应带 `rows`（每项 `row` 为 sheet 行号）/ `totalRows`（整张 sheet 行数）/ `offset` |

`sampleLimit` 为每页行数（上限 500，0 表示服务端默认），`sampleOffset` 为页首偏移（负数按 0 处理）；
翻页时只读取该窗口的行，`totalRows` 始终是整张 sheet 的数据行数，与页大小无关。
样本行的 `row` 是 **sheet 行号**（表头行为 1，数据行从表头下一行开始），问题项的 `row` 与之一致。

选择产品后，预览还会追加**字段值校验**告警：失败单元格对应的 `issues` 项为
`{"level":"WARN","code":"VALIDATION_FAILED","fieldKey":"tradeId","row":4,"message":"Value 'X' of 'Trade ID' does not match the rule ..."}`，
`fieldKey` 使向导单元格着色。`level` 取决于规则的严重级别：`WARN` 只提示（单元格标黄，**不阻断导入**），
`ERROR` 使该行无效（单元格标红，提交时该行被拒绝，`invalid` 计数加一）。

每个样本行还返回 `rawTexts`（`{"data":{字段Key:原文},"managed":{存储键:原文}}`）：导入时的单元格原始文本，
预览表按它显示，导入后同样的文本进入 `test_case.raw_json` 并用于校验、对比、记录 ID 与导出。

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
| POST | `/api/imports` | `{uploadId, sheets:[{sheetIndex, productCode, headerRow, tradeStartColumn, dataStartRow, dataEndRow}], force, includeManagedColumns, includeUnmappedColumns, stopAtEmptyRow, stopAtFirstError, taskName}`；`taskName` 可选：先按**任务 ID** 查找，再按**任务名**（忽略大小写）查找，命中时本次批次**并入该任务**（不新建任务，仅累加 `sheet_count` / `batch_count` / `total_rows` / `invalid_rows`，源文件不变，状态重新落定为最终值，不会停留在 `RUNNING`）；形如 36 位 UUID 但不存在的值返回 404（不会被当作任务名）；任务删除是硬删，名称随之释放可再次使用；空值沿用默认命名（`Untitled` / `Untitled N`）；响应新增 `taskName` 与 `joined` 标记是否并入了已有任务 | |

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
| PATCH | `/api/tasks/{id}/note` | `{note}`：设置或清除任务备注（最多 2000 字、支持多行），返回 `{id, note}` |
| PATCH | `/api/tasks/{id}/name` | 重命名任务：`name` 去空白后非空、≤256 字符、租户内唯一；重名返回 `409 CONFLICT` |
| GET | `/api/tasks/summary` | 各状态任务数：`RUNNING` / `SUCCEEDED` / `PARTIAL` / `FAILED`（恒返回，无数据为 0；**没有 `PENDING`** —— 导入是单事务，排队态不可观测）：支持与列表相同的 `kind` / `product` / `q` / `from` / `to` 过滤，但**忽略 `status`**——每张卡片报的就是「点它之后列表的总数」，两者可逐一核对 |
| GET | `/api/tasks/{id}` | 任务 + 批次 + 问题明细；任务对象含 `sourceName`、`sourceSha256` 与 `sourceMd5`（导入时按文件内容计算的 MD5，历史任务可能为 `null`） |
| GET | `/api/tasks/{id}/batches` | 任务下批次 |
| DELETE | `/api/tasks/{id}` | **硬删**（不可恢复）：任务、其全部批次（含克隆与已单独删除的批次）、这些批次的用例、这些批次的导出记录与归档 CSV 一并删除；同时断开 `external_job_case` / `external_output.imported_batch_id` 引用，并删除工作簿基线目录与该任务独占的上传文件（上传仍被其它批次引用时保留） |

任务状态：`RUNNING` → `SUCCEEDED` / `PARTIAL`（有无效行或某 sheet 失败）/ `FAILED`（导入是单事务，外部看不到排队态）；任务删除后记录不再存在（硬删）。

## 批次与用例

| 方法 | 路径 | 说明 |
| --- | --- | --- |
| GET | `/api/batches` | `product`、`q`（源文件 / Sheet / 备注 / 标签 / 产品 / 批次 ID）、`kind`、`status`、`tag`、`favorite`、`pinned`、`from` / `to`（创建日期，含首尾）、`sort=created\|name\|rows\|invalid\|product`（可加 `_asc` / `_desc`，缺省创建时间倒序）、`includeDeleted`、`includeExternal`、`page`、`size`；返回项含 `favorite`、`pinned`、`tags` |
| GET | `/api/batches/facets` | 与列表同样的筛选参数；返回 `total` / `favorite` / `pinned` 与 `products` / `kinds` / `statuses` / `tags` 各维度计数（每个维度按其余筛选条件统计） |
| GET | `/api/batches/dock` | `limit`（默认 12，≤30，置顶优先再按时间倒序）、`includeDeleted`，供控制台停靠栏使用 |
| GET | `/api/batches/{id}` | 批次详情（含 `activeCases`） |
| PATCH | `/api/batches/{id}` | `{note}`：设置或清除批次备注（空串清除，最多 2000 字、支持多行；`V10__list_note.sql` 将列宽从 500 放宽到 2000） |
| PATCH | `/api/batches/{id}/name` | `{name}`；去空白后 1~256 字符，同一任务内重名返回 409 |
| PATCH | `/api/batches/{id}/meta` | `{name?, favorite?, pinned?, tags?, note?}`，缺省字段保持原值；批次名称同 `/name` 规则（任务内唯一、≤256）；标签去空白/去重、≤10 个且每个 ≤32 字符、不得含逗号（可为 `TAG` 集合的预定义值或自由标签），备注与行内备注同字段、同规则（≤2000 字符、统一换行） |
| DELETE | `/api/batches/{id}` | 软删批次与其用例（任务删除时这些行会被一并物理清除） |
| GET | `/api/batches/{id}/validation` | 批次字段值校验（不落库、不拦截）：`ignoreCase`（忽略大小写，默认 `false`）、`skipBlank`（不校验空值，默认 `true`）；按产品字段上绑定的校验规则逐行检查，返回 `scope=BATCH` / `ignoreCase` / `skipBlank` / `rowsChecked` / `rowsFailed` / `rowsBlocked`（存在错误级失败的用例行数）/ `failures`（每条含 `caseId` / `rowNo` / `testCaseId` / `blocked` 与失败字段 `fieldKey` / `header` / `value` / `expression` / `failedRules` / `severity`）/ `fields`（字段统计：`fieldKey` / `header` / `scope` / `ruled` / `implicit`（覆盖来自值类型引用的集合，`expression` 记为 `SET:<集合码>`）/ `expression` / `checked` / `failed` / `blocked`）/ `unruledFields`（完全没有规则的字段列名）/ `truncated`（单次最多校验 2000 行） |
| POST | `/api/batches/{id}/compare` | 批次对比（只返回结果、不落库）：`{rightBatchId, mode=FIELDS\|VALUES, align=KEY\|ROW\|TESTCASE, compareOrder?}`；`FIELDS` 只比字段清单，`VALUES` 再逐用例比字段值与公式——值比较与用例对比同一套语义（交易字段与管理列都取**原始文本**，**逐字符比较**，`1000` 与 `1,000.00` 视为不同）；`align` 决定用例对齐方式（唯一键 / 行号 / 用例 ID），`compareOrder=true` 时字段顺序与用例行顺序也计入差异；每侧最多比较 1000 行（超出置 `truncated`）；与自身对比、未知模式/对齐方式返回 400，批次不存在返回 404 |
| GET | `/api/batches/{id}/cases` | `q`、`hasFormula`、`sort=row\|testcase\|key\|formulas\|updated`（可加 `_asc` / `_desc`，缺省原始顺序升序）、`withSummary`（默认 true，返回 `summary.total` / `summary.withFormula`）、`page`、`size`；另返回 `duplicates.{business,core}`（本批次内重复的 Business / Core Business Record Id，仅提示不拦截） |
| POST | `/api/batches/{id}/cases/bulk-update` | 批量改写批次内某个字段：`{fieldKey, mode=VALUE\|FORMULA\|CLEAR, value?, formula?, valueSet?, valueItem?, query?, hasFormula?, caseIds?, scope?, dryRun?}`。`caseIds` 非空时只更新这些用例，否则按 `query` / `hasFormula` 过滤整批；**两者都没有时必须显式声明 `scope=BATCH`**（默认值不存在，避免字段名写错时静默改写整批，缺失返回 400）；`scope` 可取值 `BATCH` / `FILTER` / `SELECTION`，须与负载一致；`valueSet` + `valueItem` 表示新值/公式取自【元数据】集合（集合类型须与模式一致，取枚举值的 `content`，为空时回退到 `code`）；`dryRun=true` 只返回 `matched` 与前后对照样例。写入时提交的 `value` 文本成为该字段**新的原始文本**（保留空格/千分位等写法），并同步刷新归一化副本，`CLEAR` 时两者一并删除。唯一键字段批量设同值返回 409，单次上限 `app.limits.max-bulk-field-update`（默认 5000）超限返回 413 |
| GET | `/api/cases/{id}` | 用例详情：`data` 是归一化副本（**仅**日期 / 时间 / 日期时间 / 布尔 / 数值类型，供排序、搜索、筛选），`values` 是全部交易字段的**原始文本**（展示用），`managed` 为管理列原文；另含 `formulas` / `extras` / `version` / `originalRecordId` / `businessRecordId`（含管理列原文）/ `coreBusinessRecordId`（仅交易字段原文）。业务处理一律使用原始文本（存于 `raw_json`） |
| GET | `/api/cases/{id}/vertical` | 纵向视图（`identity` / `trade` / `managed` / `extras` 分组；`identity` 含 Original / Business / Core Business Record Id） |
| PATCH | `/api/cases/{id}` | `{version, values, managed, formulas}`，版本不匹配返回 409；`values` / `managed` 中的字符串同时成为该字段**新的原始文本**（保留前后空格、千分位与原始日期写法），并同步刷新归一化副本（仅日期 / 时间 / 日期时间 / 布尔 / 数值）；`formulas` 为字段到公式的映射：非空值覆盖该字段公式（须以 `=` 开头），空串删除公式（静态值保留）；管理列与唯一键字段不接受公式（400 / 409）；保存后按原文重算 `businessRecordId`（改管理列也会变）与 `coreBusinessRecordId`（仅交易字段变化才变） |
| GET | `/api/cases/search` | 全租户用例搜索，供「用例对比」挑选对手用例：`q`（自由文本匹配 Test Case Id / Trade ID / 备注 / 任意字段值；若为 **用例 ID** 则精确定位该条，若为 **批次 ID** 则列出该批次用例，若为 **任务 ID** 则列出该任务下各批次用例）、`page`、`size`；返回 `match`（`TEXT` / `CASE` / `BATCH` / `TASK` / `NONE`）、`items`（每条含 `id` / `testCaseId` / `keyValue` / `rowNo` / `productCode` / `productName` / `batchId` / `batchName` / `taskId` / `taskName`）、`total`、`totalPages` |
| POST | `/api/cases/{id}/compare` | 用例对比（只返回结果、不落库）：`{rightCaseId}`；结果为两例的并集字段清单，每项含 `fieldKey` / `header` / `scope`（`TRADE` / `MANAGED`）/ 左右取值 / 左右公式 / `status`（`SAME` / `DIFFERENT` / `LEFT_ONLY` / `RIGHT_ONLY`，与自身对比 400、用例不存在 404）；取值**按存储文本逐字符比较**（`1000` 与 `1000.00` 视为不同），公式也按原文比较 |
| DELETE | `/api/cases/{id}` | **硬删**（不可恢复）：删除该用例行，并清除 `cloned_from` 与 `external_job_case` 中对它的引用 |
| GET | `/api/cases/{id}/validation` | 单用例字段值校验（不落库、不拦截）：`ignoreCase`、`skipBlank` 与批次端点同义，返回结构相同，`scope=CASE` |
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
| POST | `/api/exports/precheck` | 返回 `valid`、`rowCount`、`headers`、`sampleRow`、`issues`；`sampleRow` 已应用 `overrides` 并重算公式 |
| POST | `/api/exports` | 生成 CSV 并**存档为记录**（返回 `exportId`、`fileName`、`rowCount`、`size`、`warnings`、`overrides`），前端不再自动下载 |
| PATCH | `/api/exports/{id}/note` | `{note}`：设置或清除导出记录备注（空串或 `null` 清除），最多 2000 字、支持多行；返回 `{id, note}` |
| GET | `/api/exports` | 导出记录：`q`（文件名 / 产品 / 批次 ID / 导出 ID）、`product`、`batchId`、`from` / `to`、`sort=created\|rows\|size`（可加 `_asc` / `_desc`）、`page`、`size`；返回项含 `options`（含 `overrides` 明细）、`fileExists`、`downloadUrl` |
| GET | `/api/exports/{id}/preview` | 导出数据预览：`?limit=`（默认 100，上限 500），返回 `fileName` / `rowCount` / `shownRows` / `truncated` / `headers` / `rows`；按存档文件的分隔符与引号解析，行数超限时截断而非报错；文件缺失返回 404 |
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
  "recalculateFormulas": true,
  "overrides": [
    { "fieldKey": "notional", "mode": "VALUE", "value": "3000000" },
    { "fieldKey": "fixedRate", "mode": "FORMULA", "formula": "=D2/500000" },
    { "fieldKey": "direction", "mode": "CLEAR" },
    { "fieldKey": "notionalCurrency", "mode": "VALUE", "valueSet": "CURRENCY_X", "valueItem": "USD" }
  ]
}
```

`options` 中**每个字段都可省略**（省略即沿用该租户在「设置」中保存的默认值，`options` 整体省略同理），
因此只传需要的差异项即可，例如 `"options": {"lineEnding": "LF"}`。`lineEnding` 仅接受 `"\n"` / `"LF"` 与
`"\r\n"` / `"CRLF"`，`managedColumns` 仅接受 `KNOWN` / `ALL` / `NONE`，其他值返回 400，避免写出破坏结构的
文件；`delimiter` 支持 `","`、`";"`、`"TAB"` / `"\t"`、`"|"` 或单个字符。

`overrides` 为**导出前的临时更新（不写库）**：规则与批量更新一致（值按字段类型解析、公式须以 `=` 开头、
集合类型须与方式匹配、必填字段不可清空、只读标识列不可改），区别是只作用于本次导出的 CSV 与预检样例，
用例本身保持原值；公式类临时更新会参与导出前的公式重算，其他列引用该字段时同样看到新值。多行导出时若把
唯一键字段（`uniqueField`）改成同一个值，或给唯一键设置公式，直接返回 409。导出完成后各条临时更新会写入
导出记录的 `options.overrides`（`fieldKey` / `header` / `mode` / `display`），导出结果与「导出记录」据此
标记与提醒；CSV 文件本身不写入任何标记，可原样回导。

`recalculateFormulas` 缺省为 `true`：导出前用批次的不可变基线工作簿重算公式，公式字段输出重算后的值
（当前用例值先按 Excel 原生类型写回对应单元格，未导入的行与辅助列沿用基线原值）。**求值失败即拒绝导出**：
`POST /api/exports` 返回 `400`，`details.issues[].code = FORMULA_RECALC_FAILED`，消息包含列号、行号、字段列名与
Excel 错误码或 POI 的不支持原因；预检接口同样把这些记为 ERROR（`valid=false`）并给出 `sampleRow`（重算后的值）。
跨多个批次或基线缺失导致未重算时只给 `FORMULA_RECALC_SKIPPED` 警告，导出照常。传 `recalculateFormulas=false`
可跳过重算、直接输出用例存储值。

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
| PUT | `/api/settings` | 保存导出默认值；**逐字段合并**：只提交需要修改的键，未提交的键沿用已保存的值（`lineEnding` 只接受 LF/CRLF，`managedColumns` 只接受 KNOWN/ALL/NONE，其他值 400） |
| GET | `/api/settings/ui` | 界面偏好：`{issueInlineLimit}`——导入问题清单就地展开的条数上限（租户级，来自 `app.ui.issue-inline-limit` 或已保存的租户值） |
| PUT | `/api/settings/ui` | `{issueInlineLimit}`：保存界面偏好；值会被**夹到 5–200** 而不是报错，省略该键则保持原值 |
| GET | `/api/i18n` | 当前语言的文案字典 `{locale, messages}` |
| GET | `/actuator/health` | 健康检查 |

## 多语言

支持三种语言：`zh-CN`（简体中文，默认）、`zh-HK`（简体中文（香港））、`en`（英语）。

- 语言保存在 `lang` Cookie（一年有效），可用 `?lang=en` 临时切换；
- 服务端用 `messages*.properties` 渲染页面标题与静态文案，前端用 `/api/i18n` 取同一套字典；
- 顶栏语言下拉切换后会重新加载页面；
- 目前覆盖全站外壳（顶栏、导航、通用状态徽标）与「产品定义」「元数据」两个页面，其余页面保留中文，可按同一套 key 逐步迁移。

## 页面

| 路径 | 页面 |
| --- | --- |
| `/` | 任务管理 |
| `/import` | 导入向导 |
| `/tasks/{id}` | 任务详情 |
| `/batches` | 批次列表（导入 + 克隆） |
| `/batches/{id}` | 批次详情 |
| `/outputs`、`/outputs/{id}` | 产出浏览 / 详情 |
| `/products` | 产品定义（平台 → 基产品 → 产品） |
| `/meta` | 元数据 |
| `/resources` | 资源统计 |
| `/audit` | 审计日志（`app.audit.enabled=false` 时 404） |
| `/about` | 关于 |
