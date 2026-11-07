# 初版验收记录

验收日期为 2026-11-08，采用本机 Java 21.0.7 和 Maven 3.6.3。

## 构建与自动化

常规入口 `scripts/build.ps1` 执行 `mvn clean verify` 成功。共 17 项 JUnit 测试，失败 0、异常 0、跳过 0。

- UTF-8 HMAC 签名与独立 Python 黄金向量匹配。
- 实际 HTTP 请求字节与参与签名的序列化字节相同。
- 业务失败请求只发送一次，错误信息脱敏。
- AES-GCM 解密与篡改拒绝。
- 商品与各 SKU 独立增重、缺失兜底、重复计算、边界阻断。
- 多维 SKU Key、维度归属、重复组合、未知 ID、不可变标识校验。
- JSON Pointer 转义与缺失/null 差异。
- SQLite 原始快照保留未知字段，并发生成同一草稿。

## MCP 与真实读取

通过打包 JAR 及常规 `scripts/run.ps1` 入口执行 JSON-RPC。启动脚本在 JAVA_HOME 指向 Java 8 时成功从用户 JDK 目录选择 Java 21。

| 项目 | 实测结果 |
| --- | --- |
| initialize | 返回 miaoshou-mcp 0.1.0 |
| tools/list | 发现 7 个具有输入 schema 的工具 |
| 店铺工具 | 实际 SG 店铺读取成功 |
| 采集箱工具 | 实际源页与关联店铺过滤成功 |
| 商品详情工具 | 两维四 SKU 商品读取与关联校验成功 |
| 定价模板工具 | 实际 SG 模板读取成功，ID 1953645，profitPercent 为 30 |
| 快照工具 | 原始详情、商品/店铺标识及 ossMd5 写入 SQLite |
| 重量草稿工具 | 商品与四个 SKU 按原始重量增加 0.01 kg |
| 重复草稿请求 | 相同 snapshotId 返回相同 draftId |
| 预览工具 | 返回 PENDING_REVIEW 和重量差异 |
| 进程重启 | 从相同数据库成功读取先前草稿 |
| stdout | 测试收到的所有输出行均可解析为 JSON-RPC |

这些验证对远程服务执行读取，重量变更记录为本地草稿。真实商品 JSON、SQLite 数据库和凭证保留在本地忽略目录，版本库测试资源为合成样本。

## 阶段边界

本次验收完成 M0–M3 与重量草稿。内容与关键词 Provider、Muse、定价公式复现、审批与完整保存、保存后全量校验及批量处理依照任务表继续。
