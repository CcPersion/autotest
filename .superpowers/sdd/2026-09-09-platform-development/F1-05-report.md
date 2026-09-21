# F1-05 实施报告：GET/POST JSON 接口定义与用例 API

## 1. 结论与范围

- 状态：**实现与主控验证完成，等待 Sol 独立复审**。
- 已实现项目内 GET/POST 接口定义与接口用例的创建、列表、读取、更新和归档，并提供静态 OpenAPI 3.1 文档。
- 复用 V1 `api_definitions/api_cases`；未修改 V1/V2、未新增迁移或依赖，也未实现 F1-06 页面、请求发送、Runner/JMX、数据行、提取、前置和清理。

## 2. 后端实现

- 新增 `api` 领域的 Controller、Service、JdbcTemplate Repository、Record 和请求/响应 DTO。
- 接口定义保存 method、URL 模板、Path/Query/Header 声明与 NONE/JSON Body；接口用例只保存参数覆盖、类型化变量和断言，不复制或改变所属定义。
- 定义与用例写操作使用项目行串行及 revision 乐观锁；所有查询与更新限定 projectId，模块、定义和用例跨项目或路径错配统一 404。
- 归档项目拒绝定义写入；归档定义拒绝用例写入；定义/用例活动名称按所属范围大小写不敏感唯一。
- 校验器拒绝非法 URL/占位符、重复 Query/Header、未声明覆盖、非法变量引用、GET JSON Body、未知字段及错误断言；错误返回字段级 `fieldErrors` 且不回显提交值。
- 密钥引用保存前必须命中当前项目活动密钥，API 仅保存引用文本，不展开秘密。

## 3. 断言与 OpenAPI 合同

- 本阶段只支持 `STATUS + EQUALS` 和 `JSON_PATH + EXISTS/EQUALS`；STATUS 限 100..599，JSONPath 必须为非空 `$` 根表达式。
- `requestSpec/caseSpec/variables/assertions` 以 JSONB 保存，保持数组顺序及 number/boolean/null/object/array 类型。
- 新增 `docs/api/f1-05-openapi.json`，覆盖全部定义/用例路径、DTO、枚举、Session/CSRF 安全和统一错误响应。
- `OpenApiF105ContractTest` 使用 Jackson 解析，并对文档路径/方法与 Controller 约定集合做双向比对；未引入 springdoc 等生产依赖。

## 4. TDD 与问题修复

- OpenAPI 合同文件缺失时先得到 2 项红灯，再补静态文档转绿。
- 后端真实 PostgreSQL 首轮因测试中的重复资源载荷和空用例载荷不符合已冻结 schema，分别得到 400；修正测试夹具为合法请求后，名称冲突与跨项目场景按合同通过，未放宽生产校验。
- 主控静态复核发现字段校验实现已有但证据不足，补充紧凑负向矩阵，覆盖 URL、占位符、重复参数、变量 token、Body、variables 和两类断言边界。
- Sol 首轮复审发现定义更新可能让既有活动用例失效；更新定义前现已在同一事务内用候选定义重验全部活动用例，不兼容时返回 409 `CHILD_CASE_INCOMPATIBLE`，只报告用例标识和字段错误，不保存候选定义。回归覆盖删除已覆盖 Query 参数、POST JSON 改为 GET/NONE 均被拒绝且 revision/内容不变，兼容改名仍可成功。
- Sol 首轮复审发现 OpenAPI 路由合同使用手写路径集合；现改为反射读取两个真实 Controller 的类级和方法级映射，再与 OpenAPI 路径及 HTTP 方法双向比对。`ErrorResponse.details` 同步声明为对象或 `null`。
- 复审追加发现映射提取器只覆盖 GET/POST/PUT 且把路径变量名归一化；现改为读取 Spring `RequestMapping` 元数据，支持所有 `RequestMethod` 并保留变量名。新增 PATCH/DELETE 临时 Controller 夹具、端点缺失和变量改名负向断言，证明合同会失败而非静默通过。
- Sol 首轮复审发现 OpenAPI 禁止顶层额外字段，而 Spring Boot 运行时默认忽略未知字段；四个 F1-05 写请求模型现使用局部 `JsonAnySetter` 拒绝未知字段，不修改全局 Jackson 行为。真实 HTTP 回归确认定义、用例及归档请求均返回 400 `INVALID_REQUEST`，响应不回显未知字段名和值。

## 5. 新鲜验证证据

| 验证项 | 结果 |
| --- | --- |
| 修复后 F1-05 定向门禁 | 真实 PostgreSQL 集成场景 1 项、OpenAPI 合同 3 项通过；覆盖子用例兼容校验、未知字段 400 和真实 Controller 路由 |
| OpenAPI 提取器补强定向测试 | `OpenApiF105ContractTest` 3 项通过，覆盖组合映射、通用映射、PATCH/DELETE、新增端点和路径变量改名 |
| Maven 修复后总门禁 | 39 项通过：共享契约 16、Platform API 16、Runner 7 |
| 前端回归门禁 | 14 个文件、51 项通过；typecheck 与 production build 通过 |
| WSL Compose 配置 | 检查通过 |

## 6. 遗留边界

- F1-05 没有页面新增，真实浏览器保存/刷新用例属于 F1-06，因此本阶段按简报不新增 E2E。
- 当前仅能保存结构，不能发送、编译或运行；JMX 编译属于 F1-07，运行与报告属于 F1-08/F1-09。
- PUT/PATCH/DELETE 等方法、其他 Body 类型和更多断言是后续完整能力，不在本阶段宣称完成。
