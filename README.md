# OTC Clear 测试数据管理平台

HKEX OTC Clear 清算测试数据（Markitwire ATRS）的用例导入、管理、克隆与 CSV 导出工具，
并负责向外部「批量导入工具」派发任务、回收产出文件。

实现依据：OTC Clear 测试数据导入管理平台开发计划 v2.1（随任务提供的 `docs/开发计划.md`，位于独立的需求文档目录，不在本仓库内）。本仓库是该计划的落地实现。

相关文档：[REST 接口](./docs/REST接口.md)、[CSV 格式](./docs/CSV格式说明.md)、
[外部批量导入接口](./docs/外部批量导入接口.md)、[ADR-0001](./docs/ADR-0001-外部任务与文件布局.md)、
[验收清单](./docs/验收清单.md)。

## 技术栈

| 组件 | 版本 / 说明 |
| --- | --- |
| JDK | 21（`E:\tools\jdk-21.0.11+10`） |
| 构建 | Gradle 8.14.5，**多模块**，不使用 wrapper |
| 框架 | Spring Boot 4.1.2（Spring Framework 7，Tomcat 11） |
| JSON | Jackson 3（`tools.jackson`，Boot 4 默认） |
| Excel | Apache POI 5.4.1 |
| CSV | Commons CSV 1.14.1（读）+ 自研 `CsvWriter`（写） |
| 数据库 | H2 2.4.240 文件模式，每租户一个库 |
| 迁移 | Flyway（Boot 4 使用 `spring-boot-starter-flyway`） |
| 模板 | Thymeleaf 3.1 + 原生 ES Module（无 npm 构建） |
| 样板代码 | Lombok `@Getter/@Setter/@RequiredArgsConstructor/@Slf4j`；实体禁用 `@Data`，禁用 `@Jacksonized`（见 `lombok.config`） |

## 模块结构

```
ccp-playbook-manager/
├── settings.gradle.kts / build.gradle.kts      # 多项目构建，JDK 21 toolchain
├── gradle.properties                           # JDK 安装路径、并行与缓存
├── lombok.config
├── otc-clear-domain/                           # 纯领域逻辑，无 Spring、无数据库
│   └── ac.domain.{product,layout,table,casefile,clone,export,analysis,ids}
├── otc-clear-core/                             # Spring 服务、持久化、外部调用
│   └── ac.core.{config,common,persistence,service.*}
├── otc-clear-web/                              # Boot 应用、REST API、页面
│   └── ac.web.{api,dto,error,page} + resources/{templates,static,db}
├── samples/                                    # 演示数据（CSV / 含公式的 xlsx）
└── docs/                                       # 说明、接口、ADR、验收清单
```

依赖方向：`web → core → domain`。

## 启动

### 单机模式

```bash
gradle :otc-clear-web:bootJar
java -Dapp.data-root=./data -jar otc-clear-web/build/libs/otc-clear-testdata.jar --no-tenant --server.port=8080
```

### 多租户模式（一个实例绑定一个租户）

```bash
java -Dapp.data-root=./data -jar otc-clear-web/build/libs/otc-clear-testdata.jar --tenant --tenant-id=hkex-otc --server.port=8080
```

| 参数 | 行为 |
| --- | --- |
| `--tenant` + `--tenant-id=xxx` | `app.mode=TENANT`，租户 id 需匹配 `[a-z0-9-]{1,32}` |
| `--tenant` 缺 `--tenant-id` | 启动失败并给出原因 |
| `--no-tenant` | `app.mode=SINGLE`，租户固定 `default` |
| 两者同时 | 启动失败 |
| 都不传 | 默认 `SINGLE`，租户 `default` |

数据目录优先级：`-Dapp.data-root=...` > 环境变量 `APP_DATA_ROOT` > `./data`。
（数据目录必须在上下文启动前确定，因为 H2 的 JDBC URL 由它推导；因此不通过 `application.yml` 调整。）

Windows 下可直接双击 `run-single.cmd` / `run-tenant.cmd`（见仓库根目录）。

## 数据目录

```
data/tenants/{tenant}/
├── db/otc.mv.db                 # H2 数据库
├── uploads/{yyyyMMdd}/          # 上传原文件 + .meta 指纹文件（TTL 清理）
├── batches/{batchId}/           # 批次级不可变基线 source.xlsx（供导出重算公式，不随 TTL 清理）
├── exports/{yyyyMMdd}/          # 导出的 CSV
└── jobs/{yyyy}/{MM}/{jobId}/    # 外部任务文件夹
    ├── input/{jobId}.csv        # 派发输入
    └── input/manifest.json      # 派发清单
```

备份：停服后复制 `data/tenants/{tenant}` 即可；恢复时放回原位置再启动。

## 功能地图

| 页面 | 路径 | 说明 |
| --- | --- | --- |
| 任务面板 | `/` | 任务筛选/排序/搜索/删除，状态统计卡片 |
| 导入向导 | `/import` | 上传 → 参数化预览（400ms 防抖）→ 提交；未选择产品时只做原始数据预览（不套用设置项、不可提交），选择产品后按定义校验；表头行、定义起始列、数据起始/结束行可调；预览表按交易字段 / 管理列 / 未映射列分色并懒加载分页（默认 20 行）；「导入选项」控制管理列与未映射列是否落库，「空行前停止」「错误行前停止」控制扫描终止条件；状态标签可点击折叠问题区；结果面板与预览面板同宽，成功/失败分别展示，且不重复预览阶段已展示的列映射提示 |
| 任务详情 | `/tasks/{id}` | 批次列表、统计、问题明细 |
| 批次详情 | `/batches/{id}` | 用例表格、纵向浏览、编辑、克隆、导出（默认导出前重算工作簿公式）、派发 |
| 产出浏览 | `/outputs`、`/outputs/{id}` | 外部产出独立浏览（不进主用例列表） |
| 外部任务 | `/external-jobs`、`/external-jobs/{id}` | 派发、状态刷新、停止、扫描产出 |
| 产品定义 | `/products` | 平台 → 基产品 → 产品，字段继承与裁剪 |
| 字段元信息 | `/meta` | 值类型 / 角色类型 / 标签等枚举字典 |
| 关于 | `/about` | 运行模式、租户、数据目录、审计日志 |

REST API 见 [docs/REST接口.md](./docs/REST接口.md)，CSV 细节见 [docs/CSV格式说明.md](./docs/CSV格式说明.md)，
外部工具契约见 [docs/外部批量导入接口.md](./docs/外部批量导入接口.md)。

## 多语言

支持三种语言：`zh-CN`（简体中文，默认）、`zh-HK`（简体中文（香港））、`en`（英语）。顶栏下拉切换，
选择写入 `lang` Cookie；也可用 `?lang=xx` 临时切换。

- 服务端：`otc-clear-web/src/main/resources/i18n/messages*.properties`，Thymeleaf 用 `#{key}` 渲染页面标题与静态文案；
- 前端：`GET /api/i18n` 返回同一套字典，`ui.t(key, ...args)` 取词，`data-i18n` / `data-i18n-title` / `data-i18n-placeholder` 自动套用；
- 当前覆盖全站外壳与「产品定义」「字段元信息」页面；其余页面保留中文，新增文案时在三个 properties 中补同一个 key 即可逐步迁移；
- 元信息里的枚举显示名（如「文本」「关键字段」）属于用户维护的数据，不参与界面翻译。

## 产品定义模型

产品定义完全由数据库驱动，页面上的改动立即对导入、导出、克隆生效。

```
平台（固定枚举 MW / CFETS）
└── 基产品（代码自由填写、按平台各自维护，数量不限；IRS / OIS 等仅为出厂种子与输入建议）
    └── 产品（代码全局唯一）
```

- **基产品**持有该平台下的完整字段集合；不同平台的同名基产品互不影响。代码不再与平台预设绑定：MW 与 CFETS 都可继续新增任意基产品（例如 CFETS 的 NB_IRS），同一平台内代码唯一、数量不限。
- **用 CSV 快速起字段**：新增基产品时可拖入或选择 CSV，读取首行表头批量生成字段（文本 / 业务字段 / 必填），随后再通过字段管理完善类型、标签与序号（对话框内提示「使用文件快速导入字段后须通过字段管理功能继续完成基产品定义」）。
- **产品**从基产品派生字段副本（`base_field_id` 溯源），一律继承全部字段，创建后只能做减法与微调：
  - 可删除字段；可调序号、标签、备注；管理字段可选必填或选填；
  - **所有业务字段在派生产品中恒为必填**，首次继承与「同步基产品字段」时自动置为必填，编辑页也只读展示；
  - 产品拥有**独立序号**（恒为 1..N），可自由拖动排序，基产品序号仅作为「父序号」展示；
  - 身份属性（列名、别名、值类型、角色、格式、克隆模板、业务键标记）随基产品变更自动传播。
- **严格模式**：派生产品可开启「严格模式」。开启后导入的**定义区**（从「定义起始列号」开始）必须与产品的
  全部字段（业务字段 + 管理字段）**顺序、数量与列名完全一致**，多余的列一律归为**未映射列**；
  不一致时预览报 `STRICT_ORDER_MISMATCH`（区内出现产品未定义的列时报 `TRADE_BLOCK_NOT_CONTIGUOUS`）、导入直接失败。
  关闭时：业务字段按**列名**匹配（从定义起始列号开始）、管理字段按**列名**在整个表头匹配，其余列归为未映射列。
  非严格模式下产品字段的列名必须唯一，否则该产品**定义无效**、无法导入。
- **定义有效性**：非严格模式下存在重复列名的产品在派生产品列表与详情面板标记「无效」（红色），字段表内重复行
  由警告型黄色高亮改为错误型红色高亮并给出说明条；导入向导的产品下拉会标注「（无效）」并禁用提交。
  严格模式下重复列名仍只是警告型高亮。
- **同步**：基产品新增字段不会自动出现在已有产品中，需在产品上执行「同步基产品字段」（可选是否补齐缺失字段）；
  基产品删除字段会级联删除产品中的副本。
- **删除**：产品与基产品均为硬删；产品被批次或用例引用、基产品仍有产品时返回 `409`。
- **字段元信息**：值类型、角色类型、标签全部来自 `/meta` 维护的枚举集合。值类型可以引用另一个集合
  （例如 `CONV_METHOD` → `Modified Following / Flowing / Preceding`），此时该字段成为枚举字段，导入时按被引用集合校验。
- **角色类型**：`BUSINESS`（业务字段，参与交易块映射与必填校验）、`MANAGED`（管理字段，只有声明过的管理字段
  才会在导入时被识别为管理列，可选或必填）。业务键（Trade ID 一类的唯一标识列）由内部的 `key_flag` 标记维护，
  不再作为独立角色展示。
- **序号唯一**：新增或编辑字段时序号不能与其他字段重复，前端即时提示「序号 n 已被其他字段占用」，服务端返回 `409`。
- **预置模板**：`MW / IRS` 27 个字段（`_TestCaseId`、`_Remarks` + 25 个业务字段，交易块首列为必填的
  `Report Timestamp`，含重名的两个 `Rate`，默认严格模式）、
  `MW / OIS` 22 个字段（`_TestCaseId`、`_Remarks` + 20 个业务字段）、`MW / CCSIRS` 70 个字段（4 个管理字段
  `_TestCaseId`、`_Remarks`、`Scenario Group`、`Priority` + 66 个业务字段，其中前 50 个为必填）；
  另有 `IRS_DUP_DRAFT` 保留重名列且非严格，用于验证「无效」状态。派生产品从这些字段派生后按需删减。
- **字段列表**只展示业务维度：序号、列名、值类型、角色类型、标签、必填、备注；显示名、内部 Key、别名仅在字段编辑器内部保留。

### 产品定义页交互

- **紧凑表格**：产品定义与元信息页使用行高更小的密集表格，操作列改为图标按钮（编辑 / 删除 / 新增 / 锁定），序号等数字列居中。
- **编辑锁**：字段表默认只读，仅通过面板头部的锁按钮切换；解锁后才能新增、编辑、删除字段以及拖动排序，锁定状态下相关按钮禁用。
- **字段级筛选与排序**：字段搜索框按列名、别名、值类型、角色、标签、备注过滤；值类型 / 角色 / 标签下拉与「仅必填」可叠加，筛选条件以 chip 展示并可单条移除或一键清除。点击表头在「升序 → 降序 → 原始顺序」之间循环，排序状态仅由表头箭头表示。
- **拖动排序**：解锁且无筛选 / 排序时，可点击选中行并拖动到目标位置；基产品与派生产品各写自己的序号（产品序号恒为 1..N），互不影响。
- **重复列提醒**：列名按不区分大小写比较，重复时行底色使用与高亮同色（`--mark-bg`）、列名左侧加警示条并显示「重复列」徽标，表格上方汇总提示「检测到重复列」；仅为警告，不阻止编辑。
- **派生产品字段表**：第一列为产品自身序号，第二列为「父序号」（对应基产品字段序号）；同样支持筛选、排序、锁定与拖动排序，且字段集始终是基产品字段的子集。
- **新增产品**：只需填写产品代码、名称与说明，创建后继承基产品全部字段；面板底部以独立提示块展示「新增后请进入产品详情页继续完成产品定义」。
- **产品详情面板**：标题下方以副标题展示「字段 n · 用例 n」；继承说明文字所在行右侧放置锁定与同步按钮。
- **严格模式开关**：衍生产品列表在操作列前提供「严格模式」滑动开关，默认关闭；编辑对话框同样可以切换。
- **基产品标题**：面板标题行显示「平台 · 基产品 · 名称」，下方以说明性小字展示基产品描述（无描述时自动隐藏）；字段数 / 产品数只在左侧列表展示。
- **标签直选**：字段编辑面板的标签不再使用复选框，改为可直接点选的标签按钮，选中值以主题强调色实心标签展示。
- **长文本备注**：备注支持多行输入与换行保留；超过阈值的备注以省略号展示，点击弹出可自由拉伸的浮层面板（`resize: both`），面板右上角为复制图标，支持一键复制。
- **枚举值序号唯一**：新增或编辑枚举值时序号不能与同集合的其他值重复，前端即时提示、服务端返回 `409`。
- **目录搜索**：面板头部的搜索图标（位于解锁按钮左侧，快捷键 `Ctrl/Cmd + K`）打开全局搜索窗口：窗口只有一个输入框（标题 + 输入框，无底部按钮，`Esc` / 点击遮罩关闭），输入后下方才出现结果区，顶部显示搜索范围与命中数，结果按「基产品 / 产品 / 基产品字段 / 产品字段」分组；范围限定为**当前平台**的基产品与衍生产品，命中列名 / 别名 / 标签 / 备注 / 产品名称 / 代码，点击结果自动切换并高亮定位。
- **滚动条**：全局 `scrollbar-width/color` 与 `::-webkit-scrollbar` 均使用主题变量，随 11 套主题自动适配。

## 关键规则

1. **列映射只看列名**：列名去除首尾空格、忽略大小写后与产品字段的列名**完全相等**才算命中；别名、标点、
   内部空格差异一律不匹配，也不做任何相似度猜测。归类规则：
   - **严格模式**按位置比较定义区（第 k 列 ↔ 第 k 个字段，含管理字段），因此允许重名列，但定义区宽度必须等于字段数；
     定义区之外的列一律是**未映射列**。
   - **非严格模式**业务字段按列名匹配（自「定义起始列号」起）、已声明的管理字段按列名在整个表头匹配，
     其余列是**未映射列**；文件列名重复时只映射首次出现的那一列。
2. **重复列名只在严格模式合法**：非严格模式下产品字段列名重复 → 产品定义无效（列表状态显示「无效」、
   导入被 `PRODUCT_DEFINITION_INVALID` 拦截）；文件侧重复列名 → `DUPLICATE_SOURCE_HEADER` **警告**，
   仅首个同名列参与映射，其余留作未映射列。严格模式下列名重复不算错误，仅提示 `DUPLICATE_HEADER_MAPPING` 并按位置匹配。
3. **未映射列**：不参与映射的列进入 `extras`（不静默丢弃），每条给一条 `UNMAPPED_TRADE_COLUMN` 警告；
   非严格模式下若未映射列夹在交易列之间，另给一条 `TRADE_BLOCK_NOT_CONTIGUOUS` 警告（严格模式下则是错误）。
4. **三套 ID**：
   - Test Case Id：文件中 `_TestCaseId`，缺失则生成 `TC-{businessId[0..12]}`；
   - Original Record Id：`sha256(文件名|sheet|行号)[0..31]`；
   - Business Record Id：`sha256(业务字段)`，公式字段用**公式文本**参与哈希。
5. **公式**：导入时把工作簿复制一份不可变基线（`batches/{batchId}/source.xlsx`，读取前校验 `sha256`），
   公式文本按原文存 `formula_json`（只在计算 Business Record Id 时可选地掩码行号），UI 显示 `ƒ` 徽标。
   导出前默认用基线 + 当前用例值重算公式，CSV 输出重算后的值而不是源文件的缓存值；不支持求值的公式
   保留用例值并告警。
6. **管理列**：只有产品定义中声明为 `MANAGED` 的字段才算管理列，写入 `managed_json`
   （`_TestCaseId` 落到独立列）。没有声明的列（包括文件里的 `_TestCaseId` / `_Remarks`）都是**未映射列**，
   进入 `extras`；要保留它们，就在产品定义里声明为管理字段。
   出厂种子中 `MW/IRS`（27 个字段）与 `MW/OIS`（22 个字段）都声明了 `_TestCaseId`、`_Remarks`，`MW/CCSIRS` 额外声明了
   `Scenario Group`、`Priority`（合计 70 个字段）。`MW/IRS` 的 `Report Timestamp` 是业务字段（交易块首列、恒必填），
   Excel 中的时间单元格按原样保存为文本，例如 `2026-11-09T22:53:51.79`；样例 `IRS.Standard` 中
   `Report Timestamp` 与 `Trade Date` 使用 `=NOW()` / `=TODAY()`，用于验证公式保存与导出重算。
7. **产出隔离**：外部产出用例 `origin=EXTERNAL_OUTPUT`，只在 `/outputs` 出现。
8. **删除**：一律软删（`status=DELETED`），任务删除级联批次与用例。

## 构建与测试

```bash
gradle build            # 编译 + 单元 + 集成测试
gradle test             # 仅测试
gradle :otc-clear-web:generateSamples   # 重新生成 samples/
gradle :otc-clear-web:bootJar           # 产出 otc-clear-testdata.jar
```

测试覆盖：

| 层 | 位置 | 内容 |
| --- | --- | --- |
| 单元 | `otc-clear-domain/src/test` | 序列对齐（重名/缺列/乱序）、布局识别、管理列归类、三套 ID、公式捕获、CSV 写出、命名模板、表头检测、预览 |
| 集成 | `otc-clear-web/src/test` | 真实 HTTP：上传→预览→导入→任务→批次→纵向→克隆→导出、Excel 公式、并发乐观锁、租户隔离、外部任务派发/回调/扫描、页面渲染 |

## 与开发计划的偏差（已确认）

| 偏差 | 原因 |
| --- | --- |
| Flyway 用 `spring-boot-starter-flyway` | Boot 4 把 Flyway 自动配置拆到独立模块，仅 `flyway-core` 不会迁移 |
| H2 JDBC URL 不带 `AUTO_SERVER` | H2 2.4 不支持 `AUTO_SERVER=TRUE` 与 `DB_CLOSE_ON_EXIT=FALSE` 组合；单实例无需多进程访问 |
| `app.import.*` 通过 `@Name("import")` 绑定 | `import` 是 Java 关键字，字段名用 `importRules` |
| 数据目录用 JVM 参数/环境变量 | 数据目录决定 JDBC URL，需在上下文启动前确定 |
| Jackson 浮点反序列化为 `BigDecimal` | 保证 `1,500,000.00` 这类金额按原精度导出（`spring.jackson.deserialization.use-big-decimal-for-floats`） |
| 前端为服务端页面壳 + 客户端渲染 | 与参考项目一致的纯 ES Module 方式；无 npm 构建 |
| CSV 读写用 Commons CSV 1.14.1（计划 §6）而非 `jackson-data-format-csv` | 计划明确要求 Commons CSV，且需要按位置输出重名列、自定义引号策略（自研 `CsvWriter`） |

更多决策见 [docs/ADR-0001-外部任务与文件布局.md](./docs/ADR-0001-外部任务与文件布局.md)。

## 待业务确认

沿用计划附录 D：外部工具正式接口（Q1）、各产品完整字段清单（Q2）、公式规范化策略（Q3）、
导出是否保留管理列（Q4）、产出 CSV 表头是否与输入一致（Q5）。
