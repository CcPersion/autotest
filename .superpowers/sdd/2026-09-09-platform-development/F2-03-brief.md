# F2-03 提取与断言实施简报

状态：设计已由 `gpt-5.6-sol/high` 审查通过，允许 `gpt-5.6-luna/xhigh` 开始实现。本文冻结本任务范围，不代表任务已完成。

## 目标与边界

本任务补齐 HTTP 响应提取、断言、即时试算和报告事实，确保平台试算结果与固定 JMeter 5.6.3 运行结果一致。只处理 HTTP 相关能力；SQL/Redis 留给 F2-06/F2-07，不在本任务重做。不得把当前浏览器内简化求值继续作为正式语义。

## 提取器合同

资产结构保持兼容：

```json
{
  "type": "JSON_PATH | JMESPATH | XPATH | REGEX | HEADER | COOKIE",
  "expression": "string",
  "variable": "合法普通变量名",
  "defaultValue": "可选 JSON 值",
  "failIfMissing": true
}
```

- `matched` 只表示表达式真实命中；默认值不能把未命中改成命中。
- `usedDefault` 表示未命中后是否使用默认值；`failIfMissing` 与默认值相互独立。
- 命中值和默认值均可进入后续上下文；JSONPath/JMESPath 的对象、数组、数字、布尔和 `null` 保留类型。
- 非确定多结果按 JMeter `matchNumber=1` 取首个结果；确定表达式返回的数组保持数组。
- 正则有捕获组取第一组，无捕获组取完整匹配；Header 名称大小写不敏感，Cookie 名称精确匹配。
- XPath 使用禁用 DTD/外部实体/外部资源的安全 XML 解析；表达式和响应体有长度上限。

## 断言合同

统一结构为 `type/operator/expression/expected`。支持：

- `STATUS/EQUALS`；`BODY` 的 `EQUALS/CONTAINS/NOT_CONTAINS/MATCHES`。
- `JSON_PATH`、`JMES_PATH` 的 `EXISTS/NOT_EXISTS/EQUALS/NOT_EQUALS/GREATER_THAN/LESS_THAN/CONTAINS`。
- `XPATH` 的 `EXISTS/NOT_EXISTS`。
- `HEADER`、`COOKIE` 的 `EXISTS/NOT_EXISTS/EQUALS/CONTAINS/NOT_CONTAINS/MATCHES`；新建规则必须以 `expression` 指定目标名称，旧的无名称资产按兼容模式读取。
- `SCHEMA/VALIDATE`、`RESPONSE_TIME/LESS_THAN`、`VARIABLE` 的 `EQUALS/NOT_EQUALS/GREATER_THAN/LESS_THAN/CONTAINS`。

大于/小于只接受十进制数值；相等比较保留 JSON 类型；同一步全部断言必须执行并逐条回传。JSON Schema 禁止远程 `$ref`，若引入标准实现必须固定版本并通过依赖审查。

## 即时试算 API

新增 `POST /api/v1/projects/{projectId}/extractor-trials`。请求只携带用户提供的响应样本和提取器数组；接口只计算样本，不访问目标地址、不写数据库、不解析密钥。响应按规则返回 `ruleIndex/type/expression/variable/matched/usedDefault/value/valueType/failed/message`。单条表达式非法时仅该项返回 `failed=true,errorCode=INVALID_EXPRESSION`，其他规则继续计算；结构错误或超长输入返回 400。

运行时和试算必须调用同一受控表达式实现或同版本语义适配层。前端只负责输入和展示，不再保留独立的 JSONPath/JMESPath/XPath 求值分支。

## 报告事实

提取事实增加规则序号、表达式、`matched`、`usedDefault`、类型、失败标志和消息；断言事实增加稳定规则序号、表达式、期望值、实际值、操作符、结果和消息。JMX 断言名称必须包含 `ruleIndex`，两个同类型断言也必须能准确关联。

## 实施与验收顺序

1. 先写合同红测和共享表达式评估测试，再改生产代码。
2. 修 Runner 默认值命中语义、对象/数组类型和提取事实。
3. 补 JSON/JMES 数值比较、变量断言、Header/Cookie 目标定位、Schema 合同和逐项编号。
4. 增加平台试算 API，接入 Vue，移除浏览器自制求值作为正式路径。
5. 完成固定 JMeter 5.6.3 CLI → JTL → Runner 上传 → Platform 报告的双断言同时失败闭环。
6. 运行六类提取命中/未命中/默认/非法表达式、所有断言操作符、XPath XXE、超长输入、恶意正则、敏感头 Cookie、试算不触网不落库等测试。

未完成上述验证前，不得宣称 F2-03 完成；不得把 SQL/Redis 任务提前并入本任务。
