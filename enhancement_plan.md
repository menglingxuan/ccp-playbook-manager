# 探索性测试与优化计划（Enhancement Plan）

> 生成时间：2026-11-16
> 范围：全项目（`otc-clear-domain` / `otc-clear-core` / `otc-clear-web`，约 27k 行 Java + 9k 行前端 JS）
> 状态：第 1 节「已修复」已实现并验证；第 2 节清单中带 ✅ 的条目已在第二轮实施完成，其余仍待评估。

## 进度（第二轮，2026-11-16）

**已完成**：A1、A3、A4、A5、A6、B1、B3、B4、B5、B6、C1、C2、C3、C8、C9、C10、C11、C12、D1、D2、D3、D4、D5、D6、D7、D8、D9、D10、D11

**仍待评估**：A2（CSV 公式注入，需确认是否默认中和）、A7、A8、B2（重复导入按 sha256）、C4、C5、C6、C7、C13、E1~E6

**本轮验证**：`gradle build` 全绿（domain 95 + web 172 = 267 个测试，1 个既有跳过）；实机 8080 重启后 9 个页面全部 200、无控制台错误；接口实测：嵌套量词规则 400、样本超长提示、500 行 CSV 预览 `dataRows=500`、批量更新缺 scope 400、设置部分更新保留其他字段、定义预检面板与校验面板在统一抽屉下正常工作。

## 0. 测试方法

1. **静态扫描**：分三条线（后端正确性/性能、前端 JS 与模板、文件 IO 与输入校验安全）逐文件核对，只保留读过代码可复现的条目。
2. **运行时探索**：独立实例（端口 8081、独立数据根、租户 `explore`）上做破坏性测试，不触碰 8080 真实数据。覆盖：CSV 上传与导入边界值、原始文本保真、批量更新、校验选项、导出选项与文件内容、定义导出/导入、分页与查询参数边界、ReDoS 计时、CSV 注入。
3. **UI 实测**：Playwright 走查 9 个页面，检查控制台报错、布局、状态灯与提示条表现、抽屉交互。
4. **回归验证**：`gradle build`（domain 95 + web 165 = 260 个测试全绿），重启实机复核接口与页面。

---

## 1. 已修复（含回归测试）

| # | 问题 | 修复位置 | 实测表现（修复前 → 修复后） |
|---|---|---|---|
| 1 | 分页 `page` 超出 `int` 范围导致 offset 溢出 → 500 | `otc-clear-core/src/main/java/ac/core/common/PageQuery.java` (`offset()` 改 `long` 计算并夹取) | `?page=2147483647&size=100`：500 → 200 空页 |
| 2 | 查询参数类型错误未映射，返回 500 | `otc-clear-web/src/main/java/ac/web/error/GlobalExceptionHandler.java`（新增 `MethodArgumentTypeMismatchException` / `HttpMessageNotReadableException` → 400） | `?size=abc`：500 → 400 `Parameter 'size' has an invalid value: abc` |
| 3 | 引号未闭合等格式错误的 CSV 抛未检查异常 → 500 | `otc-clear-domain/src/main/java/ac/domain/table/CsvTableReader.java`（捕获 `UncheckedIOException` 并转 `TableReadException`） | 上传损坏 CSV：500 → 400 + 具体原因 |
| 4 | `TYPE_MISMATCH` / `FORMULA_ERROR` 丢失行号，单元格级问题无法定位 | `otc-clear-domain/src/main/java/ac/domain/casefile/RowConverter.java`（`parseCell` 传入 `sourceRow` 并 `atRow`） | 预览样本问题 `row:null` → `row:7` / `row:8` |
| 5 | 定义导入中一个非数字单元格让整份工作簿 400（违背「无效项跳过」设计） | `otc-clear-core/src/main/java/ac/core/service/definition/DefinitionImportService.java`（逐行 try/catch + `readNumericCells` 预读数值列） | `sort_order=not-a-number`：400 全拒 → 该行 `INVALID` 并给出原因（1 行无效 + 21 行未变化），`apply` 只跳过该行 |
| 6 | 导出 `options` 只传部分字段 → NPE 500，且分隔符变成 `NUL`、表头丢失 | 新增 `otc-clear-core/src/main/java/ac/core/service/export/CsvOptionsPatch.java`；`ExportRequest` 改用它；`ExportService.options()` 逐字段合并租户默认值 | `{"options":{"lineEnding":"LF"}}`：500 → 200，分隔符 `,`、表头存在、LF 生效；顺带封堵 `lineEnding` 注入（任意串 → 400）与非法 `managedColumns` → 400 |
| 7 | 导入预览表头与产品下拉未转义（标记注入；CSP 已挡住脚本执行，但可破坏表格结构） | `otc-clear-web/src/main/resources/static/js/pages/import.js`（表头与 `<option>` 全部 `ui.escapeHtml`） | 表头 `<img src=x onerror=…>`：DOM 变为 `&lt;img…&gt;`，`#previewHead img` 计数 0 |
| 8 | 预览状态灯误导：样本有无效行仍显示绿色「校验通过」 | `.../static/js/pages/import.js`（`renderPreview` 计入 `stats.sampleInvalidRows`） | 「校验通过」（绿） → 「样本中 3 行存在问题」（琥珀） |
| 9 | 提示条 8 处用 `bad`、3 处用 `warn`，CSS 只认 `error`/`ok` → 错误提示显示为默认蓝且 3.5 秒消失 | `.../static/js/ui.js`（别名归一） + `.../static/css/app.css`（新增 `.toast.warn`） | `bad` → `.toast.error`（红、7s），`warn` → 琥珀色 |
| 10 | 校验面板每次「刷新」都重复绑定遮罩点击，监听器累积 | `.../static/js/validation-panel.js`（绑定移出 `paint()`） | 仅绑定一次 |
| 11 | 定义备份抽屉不支持 `Esc` 关闭（其他抽屉都支持） | `.../static/js/definition-backup.js`（新增 keydown 监听 + 清理） | Esc 可关闭 |
| 12 | 端到端测试缺口 | 新增 `otc-clear-web/src/test/java/ac/web/InputRobustnessTest.java`（5 例）、`ExportManagementTest` 新增 2 例 | 260 个测试全绿 |

文档同步：`docs/验收清单.md` 新增「健壮性与安全性修复（探索性测试）」章节；`docs/REST接口.md` 补充「`options` 可部分提交 + 取值白名单」。

---

## 2. 待评估清单（未实施）

### A. 安全（按可利用性排序）

| # | 严重度 | 问题 | 证据 | 建议方向 |
|---|---|---|---|---|
| A1 | 🔴 高 | 用户可保存的校验正则无长度限制、无匹配超时；`(.*a){14}` 对 30 字符文本实测 4.8 秒（本机 JDK 21），导入时按「单元格 × 行数」放大；`POST /api/meta/rules/match` 可被直接用来打满工作线程 | `otc-clear-domain/src/main/java/ac/domain/validation/ValidationRule.java`、`otc-clear-core/src/main/java/ac/core/service/meta/MetaService.java`（`testPattern`） | 表达式/样本长度上限 + 匹配超时（独立线程中断或换 RE2/J）+ 单元格文本长度上限 |
| A2 | 🟠 中 | CSV 公式注入：导入值 `=HYPERLINK("http://evil","x")` 导出后原样写入，Excel 打开即执行 | `otc-clear-domain/src/main/java/ac/domain/export/CsvWriter.java`（`escape()` 无公式前缀中和）；已实测导出文件含未中和值 | 对 `= + - @ \t` 开头的值加前缀 `'`，或提供「导出时中和公式」开关（默认开） |
| A3 | 🟠 中 | CSV 预览把整份文件读入内存（`maxRows` 参数被丢弃），55MB CSV 至少两份副本，可反复调用 | `otc-clear-core/src/main/java/ac/core/service/importflow/PreviewService.java`（`readSheet` CSV 分支）、`CsvTableReader.read` 用 `Files.readString` | 流式解析并单独统计总行数，只保留前 N 行；增加最大列数 / 单元格长度上限 |
| A4 | 🟠 中 | XLSX 仅设 `minInflateRatio`，无 `setMaxEntrySize/setMaxTextSize`，zip bomb 可致 OOM | `otc-clear-domain/src/main/java/ac/domain/table/ExcelTableReader.java` | 显式设置 POI 解压上限；必要时改流式读取 |
| A5 | 🟠 中 | 错误响应回显绝对路径（数据根、文件路径） | `CsvTableReader`（`File does not exist: <path>`）、`UploadStore`，经 `GlobalExceptionHandler` 透出 | 对外只回逻辑文件名，路径仅写日志 |
| A6 | ⚪ 低 | 数据根字符串直接拼进 H2 JDBC URL，含 `;` 可注入 `INIT=RUNSCRIPT` | `otc-clear-core/src/main/java/ac/core/config/StartupModeResolver.java` | 解析后做字符白名单，拒绝 `;` |
| A7 | ⚪ 低 | 上传文件名净化只防 `..` 与控制符，放行 Windows 保留名、`:`、结尾点/空格 | `otc-clear-core/src/main/java/ac/core/service/importflow/UploadStore.java` | 白名单 `[A-Za-z0-9._-]` + 保留名黑名单 |
| A8 | ⚪ 低 | `xls/xlsx` 仅按扩展名判定，不校验 magic bytes | `UploadStore.detectType` | 解析前校验文件头与内容类型白名单 |

### B. 数据正确性

| # | 严重度 | 问题 | 证据 |
|---|---|---|---|
| B1 | 🟠 中 | SQL 导出的 `schema_hash` 写死 `NULL` 且脚本无重算语句 → 重放后 `SCHEMA_HASH_MISMATCH` 告警永久失效 | `otc-clear-core/src/main/java/ac/core/service/definition/DefinitionSqlWriter.java`（`..., schema_hash, ... , NULL, ...`）；Excel 路径有 `refreshSchemaHashes`，SQL 路径没有 |
| B2 | 🟠 中 | 重复导入保护按 `uploadId` 判断，而每次上传都是新 `uploadId` → 同一文件（同 sha256）不带 `force` 也可无限重复导入 | `otc-clear-core/src/main/java/ac/core/service/importflow/ImportService.java`（`duplicateImport` → `existsActiveImportForUpload`）；已实测两次均成功 |
| B3 | 🟠 中 | 导入结果口径矛盾：`stopAtFirstError=false` 时含错误行也会写入，但字段注释是「valid rows stored / rows skipped」，UI 显示「有效行 7 / 无效行 3」（7 行里 3 行无效） | `ImportService`（`totalValid` / `totalInvalid`）、`.../static/js/pages/import.js`（结果卡文案） |
| B4 | ⚪ 低 | `bulk-update` 以 `caseIds` 是否为空决定范围，未知属性被 Jackson 静默忽略；传错字段名（如 `ids`）会作用于整批 | `otc-clear-core/src/main/java/ac/core/service/cases/BulkFieldUpdateRequest.java`；实测 `{"ids":["nope"]}` → `matched:7` |
| B5 | ⚪ 低 | 分页响应回显请求页码而不夹取（`page:2147483647` 配 `totalPages:1`），前端分页器可能错位；`tasks.js` 也未回写 `state.page` | `otc-clear-web/src/main/java/ac/web/api/BatchController.java`、`.../static/js/pages/tasks.js` |
| B6 | ⚪ 低 | 设置 `PUT /api/settings` 为全量替换（只传 `bom` 会把分隔符重置为 `,`） | `otc-clear-web/src/main/java/ac/web/api/SettingController.java` |

### C. 性能

| # | 影响 | 问题 | 证据 |
|---|---|---|---|
| C1 | 高 | 单批次导出把同一批 `TestCaseRecord` 全量加载 3 次（`export` → `precheck` → `content`），并一次性构建全部行 → 内存约 4~5 倍 | `otc-clear-core/src/main/java/ac/core/service/export/ExportService.java`（`select` 三处调用、`content` 建全量 `rows`） |
| C2 | 高 | 导出预检在逐行循环内查询同一个批次（10 万行 = 10 万次 SQL） | `ExportService`（`for (record : selection.records()) { batchRepository.find(...) }`） |
| C3 | 中 | 删除元数据集合/条目时对全部基产品 × 全部产品 × 全部字段逐条查询并重复解析表达式 | `otc-clear-core/src/main/java/ac/core/service/meta/MetaService.java`（`countFieldUsage`） |
| C4 | 中 | 资源统计 `Files.walk(...).toList()` + 逐文件 `size()` | `otc-clear-core/src/main/java/ac/core/service/stats/ResourceStatsService.java` |
| C5 | 中 | 批次列表的标签聚合把租户内全部批次的 `tags` 列读进内存再拆分 | `otc-clear-core/src/main/java/ac/core/persistence/ImportBatchRepository.java`、`BatchService` |
| C6 | 中 | 导出记录列表逐行 `Files.isRegularFile`（200 行 = 200 次 stat） | `otc-clear-web/src/main/java/ac/web/api/ExportController.java` |
| C7 | 中 | 批量改字段逐行 UPDATE（上限 5000 行 → 5000 次往返） | `otc-clear-core/src/main/java/ac/core/service/cases/CaseService.java` |
| C8 | 中 | 全局搜索对每个 task / 产品单独查库（N+1），产品名解析异常还被静默降级为 code | `otc-clear-core/src/main/java/ac/core/service/cases/CaseSearchService.java` |
| C9 | 低 | 批次比较 `RIGHT_ONLY` 用 `stream().anyMatch` 去重（O(n²)）；侧栏统计用截断后的行数（显示 1000 而非真实行数） | `otc-clear-core/src/main/java/ac/core/service/batch/BatchCompareService.java` |
| C10 | 低 | 一次导出重复读取 `app_setting` 3~4 次 | `ExportService` |
| C11 | 低 | 导出写临时文件失败时不删除 `.tmp`；批次基线与导出文件没有回收任务（磁盘只增不减） | `ExportService`（`Files.move` 失败分支）、`ac/core/service/baseline/BaselineStore.java`，全局仅 `UploadStore.cleanExpired` 一个定时任务 |
| C12 | 低 | 定义导入计划缓存为全局 LRU（20 份、无租户维度），并发时可能淘汰他人预览 | `DefinitionImportService`（plan 缓存淘汰逻辑） |
| C13 | 低 | `GROUP BY` / `ORDER BY` 列名直接拼接 SQL（当前调用方传字面量，签名对外暴露 String） | `ImportBatchRepository` |

### D. 前端 / UX / 可访问性

| # | 问题 | 证据 |
|---|---|---|
| D1 | 「执行更新」与「保存」无二次确认、请求期间不禁用按钮 → 双击重复提交 | `.../static/js/pages/batch.js`、`.../static/js/pages/case-drawer.js` |
| D2 | 列表/抽屉加载无请求序号或 `AbortController`，慢响应覆盖新数据；切换批次时新加载被 `skipped` 静默丢弃 | `.../static/js/pages/tasks.js`、`batches.js`、`case-drawer.js` |
| D3 | 全选与分页不一致：第 1 页全选后翻页取消表头，第 1 页仍选中 → 可能误删 | `.../static/js/pages/batch.js` |
| D4 | 模态框无初始焦点、无焦点陷阱、关闭后不还原焦点，`role="dialog"` 缺 `aria-labelledby` | `.../static/js/ui.js` |
| D5 | `?focus=` 直接进 `querySelector`，含 `"` 时抛 `SyntaxError` 中断 `init()` | `.../static/js/pages/exports.js` |
| D6 | 审计行展开触发整页重载（滚动位置/焦点丢失） | `.../static/js/pages/audit.js` |
| D7 | 元数据条目与字段表全量渲染 + 行内 O(n²) `find`；用例抽屉字段过滤无防抖 | `.../static/js/pages/meta.js`、`.../static/js/field-table.js`、`case-drawer.js` |
| D8 | debounce 在 8 个文件重复实现且延迟不同；抽屉/面板 4 套实现行为不一致（Esc、遮罩、清理） | `tasks.js`、`batches.js`、`batch.js`、`exports.js`、`outputs.js`、`audit.js`、`output-detail.js`、`products.js` |
| D9 | `api.js` 对 2xx 响应体直接 `JSON.parse`，网关返回 HTML 时报 `SyntaxError`，错误信息不可读 | `.../static/js/api.js` |
| D10 | 列表首屏无加载态/空态，慢网络先显示「全部任务 0」；搜索框缺可访问名 | `.../static/js/pages/tasks.js`、`.../templates/index.html` |
| D11 | 导入向导「有效行 / 无效行」文案与口径不符（见 B3）；滚动加载失败时 `sampleLimit` 已自增，重试会跳页 | `.../static/js/pages/import.js` |

### E. 设计/契约层面（需决策）

1. **审计开关**：`auditEnabled=false` 时 `/api/audit` 与 `/audit` 页面均为 404，导航无入口——是否需要在「关于」中说明该开关，或隐藏相关说明？
2. **设置接口语义**：`PUT /api/settings` 为全量替换（B6），是否改为部分更新？
3. **无鉴权**：默认绑 `127.0.0.1`；若将来对外暴露，需要在 `SecurityHeadersFilter` 之外补认证、CSRF 与限流。
4. **CSV 公式中和**（A2）：会改变「所见即所得」的原文，是否作为默认开启的选项？
5. **定义 SQL 导出**（B1）：在脚本尾部追加重算语句，还是明确「SQL 仅作数据备份，恢复后需重算」？
6. **重复导入保护**（B2）：改为按 `sha256 + sheet` 判断，还是保持现状（依赖用例级重复检测）？

---

## 3. 建议执行顺序

1. **P0**：A1（ReDoS）、A2（CSV 注入）、B2（重复导入）——风险与成本比最高。
2. **P1**：C1 + C2（导出性能）、A3 + A4（大文件防护）、B1（`schema_hash`）。
3. **P2**：C3~C8、D1~D5（误操作与竞态）、E1~E3 决策项。
4. **P3**：D6~D11、C9~C13（体验与可维护性）。

## 4. 复现与验证方式

- 构建与测试：`gradle --console=plain build`（需 `JAVA_HOME=E:\tools\jdk-21.0.13+9`，Gradle 位于 `E:\tools\gradle-8.14.5-all`）。
- 单实例实机：`java -Dspring.profiles.active=prod "-Dapp.data-root=<abs>" -jar otc-clear-web/build/libs/otc-clear-testdata.jar --tenant --tenant-id=<id> --server.port=<port>`。
- 破坏性验证请使用独立数据根与独立端口，避免污染真实数据；结束后清理临时目录。
