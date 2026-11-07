# 妙手商品编辑 MCP

Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 与 SQLite 的本地 STDIO 服务。当前版本为 `0.1.0`。

初版完成 M0–M3 的基础能力，并提供已确认重量规则的本地草稿预览。后续里程碑见 [开发任务](temp-doc/task.md)。

## 启动

准备 Java 21 和 Maven 3.6.3 或更新版本。PowerShell 7 下执行以下命令。

```powershell
cd E:\miaoshouMCP
$env:JAVA21_HOME = '你的 JDK 21 目录'
./scripts/build.ps1
$env:MIAOSHOU_APP_KEY = '你的 AppKey'
$env:MIAOSHOU_APP_SECRET = '你的 AppSecret'
./scripts/run.ps1
```

脚本仅为当前进程选择 Java 21。应用日志输出到 stderr，stdout 用于 MCP JSON-RPC。数据库默认存放于项目 `.data/miaoshou.db`，由启动脚本固定工作目录。

MCP 客户端配置示例如下。凭证由客户端环境配置或安全存储提供，示例值需自行填写。

```json
{
  "mcpServers": {
    "miaoshou": {
      "command": "pwsh",
      "args": ["-NoProfile", "-File", "E:/miaoshouMCP/scripts/run.ps1"],
      "env": {
        "JAVA21_HOME": "你的 JDK 21 目录",
        "MIAOSHOU_APP_KEY": "你的 AppKey",
        "MIAOSHOU_APP_SECRET": "你的 AppSecret"
      }
    }
  }
}
```

数据库可用 `MIAOSHOU_DB_URL` 指定绝对路径，父目录需预先创建。没有 API 凭证时可启动 MCP 和查看工具定义，远程读取会返回配置错误。

## 已实现工具

| 工具 | 用途 |
| --- | --- |
| `miaoshou_list_shops` | 按站点分页读取 TikTok 店铺 |
| `miaoshou_list_collect_items` | 读取账号级未发布商品源页，按关联店铺过滤 |
| `miaoshou_get_product_detail` | 读取完整店铺模式商品并核对 ID 与 SKU 组合 |
| `miaoshou_list_price_templates` | 读取实际定价模板配置 |
| `product_capture_snapshot` | 保存完整、不可变的原始详情快照 |
| `product_prepare_weight_draft` | 从快照生成重量草稿，相同快照重复调用返回同一草稿 |
| `product_preview_changes` | 查看待审核草稿和 JSON Pointer 差异 |

采集箱分页使用妙手账号源页。即使当前页过滤后无店铺匹配项，也应根据 `hasMoreSourcePages` 继续翻页。

## 已冻结规则

- 使用定价模板 `1953645`，名称为“新加坡利润40%”，实际 `profitPercent=30`。
- 商品重量和各 SKU 重量从原始快照独立增加 `0.01 kg`，计算采用 `BigDecimal`。
- SKU 原始重量为 null 或缺失时，以商品原始重量作为基线再增加 `0.01 kg`。
- 商品原始重量缺失或重量超出 `0.001–100 kg` 范围时阻断草稿生成。
- SKU ID、Map Key、规格顺序、库存和仓库映射保持原值。

重量草稿仅保存到本地数据库，状态为 `PENDING_REVIEW`。保存审批、远程写入及回读校验属于 M7–M8。M4–M6 将接入内容优化、图片 Provider 和经过对照验证的定价引擎。

## 凭证

API 凭证从进程环境读取，数据库和日志不保存凭证。也可用 `MIAOSHOU_SECRET_ENCRYPTED` 搭配独立的 `MIAOSHOU_SECRET_KEY`。加密配置格式为 Base64 编码的 `12 字节随机 nonce + AES-256-GCM 密文与 16 字节认证标签`，密钥为 Base64 编码的 32 字节随机密钥。配置错误统一返回脱敏提示。

## 验证

```powershell
./scripts/build.ps1
```

测试包含独立 HMAC 黄金向量、签名与 HTTP 请求字节一致性、错误脱敏、重量缺失兜底、重复计算、上限校验、多维 SKU 关联、未知字段保留及 SQLite 并发草稿幂等性。用合成数据执行自动化验证，实际商品快照与 API 凭证留在本地。

设计来源与实现范围见 [需求](temp-doc/nxq.md)、[技术方案](temp-doc/technical-design.md)、[接口映射](temp-doc/api-mapping.md)、[验收计划](temp-doc/test-plan.md) 和 [初版验收记录](temp-doc/test-results.md)。
