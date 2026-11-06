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
| 导入向导 | `/import` | 上传 → 参数化预览（400ms 防抖）→ 提交 |
| 任务详情 | `/tasks/{id}` | 批次列表、统计、问题明细 |
| 批次详情 | `/batches/{id}` | 用例表格、纵向浏览、编辑、克隆、导出、派发 |
| 产出浏览 | `/outputs`、`/outputs/{id}` | 外部产出独立浏览（不进主用例列表） |
| 外部任务 | `/external-jobs`、`/external-jobs/{id}` | 派发、状态刷新、停止、扫描产出 |
| 产品定义 | `/products` | 字段顺序、重名标记、克隆模板 |
| 关于 | `/about` | 运行模式、租户、数据目录、审计日志 |

REST API 见 [docs/REST接口.md](./docs/REST接口.md)，CSV 细节见 [docs/CSV格式说明.md](./docs/CSV格式说明.md)，
外部工具契约见 [docs/外部批量导入接口.md](./docs/外部批量导入接口.md)。

## 关键规则

1. **列映射按位置对齐**：交易块表头与产品字段序列做 Needleman–Wunsch 全局对齐，
   因此允许列名重复、允许缺列、允许乱序；不使用列名 Map。
2. **三套 ID**：
   - Test Case Id：文件中 `_TestCaseId`，缺失则生成 `TC-{businessId[0..12]}`；
   - Original Record Id：`sha256(文件名|sheet|行号)[0..31]`；
   - Business Record Id：`sha256(业务字段)`，公式字段用**公式文本**参与哈希。
3. **公式**：缓存值入库，公式文本与字段关联存 `formula_json`，UI 显示 `ƒ` 徽标。
4. **管理列**：交易块左右两侧的列归入 `managed_json`；`_TestCaseId` 落到独立列。
5. **产出隔离**：外部产出用例 `origin=EXTERNAL_OUTPUT`，只在 `/outputs` 出现。
6. **删除**：一律软删（`status=DELETED`），任务删除级联批次与用例。

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
