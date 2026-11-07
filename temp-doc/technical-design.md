# 技术方案

## 组件

初版采用 Java 21、Spring Boot 3.5.16、Spring AI 1.1.8 MCP STDIO、WebClient、Jackson、MyBatis-Plus 3.5.18 和 SQLite JDBC 3.54.1.0。

Spring AI 选用与 Boot 3 系列兼容的 1.1 维护线。依赖版本固定在 pom.xml，Java 21 由脚本显式选择。

- `mcp` 将业务服务声明成工具，参数提供商品或快照标识。
- `client` 统一 JSON 序列化、HMAC、路径枚举、请求节流、超时和错误脱敏。
- `service` 承担身份校验、SKU 校验、快照、重量规则与差异比较。
- `repository` 通过 MyBatis 映射 SQLite。MyBatis-Plus 提供 Boot 集成。
- `domain` 提供不可变快照和草稿记录。

完整商品使用 Jackson JsonNode 保留未知字段。浮点 JSON 解析为 BigDecimal。SKU Map 原 Key 保留，校验时按值 ID 找到维度，支持 Key 顺序与维度顺序不同，以及合法稀疏组合。

## 状态与幂等

快照表仅提供插入和读取，包含完整接口 data、ossMd5、商品和店铺标识。重量草稿状态为 PENDING_REVIEW。同一快照与规则版本的组合有数据库唯一约束，事务内插入使用 ON CONFLICT DO NOTHING，并发请求返回同一草稿。

M7 将增加审批状态迁移和版本冲突检查。保存前重新读取并检查基线，再合并经过审批的字段白名单；网络结果不确定时先回读确认，使用验证记录判断后续动作。

## 数据与权限

初版 API 路径枚举仅包含读取接口。工具无法提供任意 API 路径。凭证从环境读取，支持外部密钥解密 AES-GCM 密文。MCP stdout 保持 JSON-RPC，日志写入 stderr，错误不透传 HTTP 请求和原始响应正文。

商品库存、SKU ID、关联 Key、原始快照受到业务约束。远程保存将在审批工具完成后实现，目标状态为 SAVED_PENDING_REVIEW。

## 后续 Provider

M4 已接入 DeepSeek LlmProvider 与 ManualKeywordProvider，详细方案见 M4-technical-design.md。M5 对接独立 ImageProvider/Muse，执行质量校验并记录图片空间上传结果。M6 复现官方运费等模板参数的定价公式，并用实际后台价格作为基准。

## 参考资料

- [Spring AI 1.1 MCP Server](https://docs.spring.io/spring-ai/reference/1.1/api/mcp/mcp-server-boot-starter-docs.html)
- [Spring AI Tool Calling](https://docs.spring.io/spring-ai/reference/1.1/api/tools.html)
- [MyBatis-Plus 安装](https://baomidou.com/getting-started/install/)
