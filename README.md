# 妙手商品编辑 MCP

Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 与 SQLite 的本地 STDIO 服务。当前版本为 `0.1.0`。

当前实现包含 M0–M3 基础、重量草稿以及 M4 DeepSeek 文本优化。里程碑与阶段边界见 [开发任务](temp-doc/task.md)。

M5.1 已完成图片资产提取与引用映射，并按用户确认适配真实 SKU 字段。商品图暂为 UNCLASSIFIED，首图仅标记主图候选。进度见 [M5 任务](temp-doc/M5/M5-task.md)，阶段结果见 [M5.1 测试报告](temp-doc/M5/M5.1-test-report.md)。

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

文本优化配置采用 `LLM_PROVIDER=deepseek`、`DEEPSEEK_API_KEY`、`DEEPSEEK_BASE_URL` 和 `DEEPSEEK_MODEL`。默认模型为 `deepseek-flash`，地址为 `https://api.deepseek.com`。Windows 启动脚本在进程未配置时读取同名用户环境变量。密钥留在本机环境，示例文件保持空值。

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
| `product_analyze` | 从快照生成带来源引文的商品分析 |
| `product_research_keywords` | 合并人工关键词与模型推荐词，标注来源 |
| `product_optimize_content` | 根据已校验分析生成英文标题和固定 HTML 描述草稿 |
| `product_translate_sku` | 翻译全部规格名称和值，保留原始关联结构 |
| `product_optimize` | 顺序执行分析、关键词、内容和 SKU 翻译，生成完整文本草稿 |
| `product_get_generation_record` | 查询模型、Prompt、输入摘要、输出、校验及审核状态 |
| `product_image_extract_assets` | 从已有本地快照提取图片资产、全部引用位置与原始 SKU Key 关联 |

采集箱分页使用妙手账号源页。即使当前页过滤后无店铺匹配项，也应根据 `hasMoreSourcePages` 继续翻页。

## 已冻结规则

- 使用定价模板 `1953645`，名称为“新加坡利润40%”，实际 `profitPercent=30`。
- 商品重量和各 SKU 重量从原始快照独立增加 `0.01 kg`，计算采用 `BigDecimal`。
- SKU 原始重量为 null 或缺失时，以商品原始重量作为基线再增加 `0.01 kg`。
- 商品原始重量缺失或重量超出 `0.001–100 kg` 范围时阻断草稿生成。
- SKU ID、Map Key、规格顺序、库存和仓库映射保持原值。

重量和文本草稿保存到本地数据库，状态为 `PENDING_REVIEW`。保存审批、远程写入及回读校验属于 M7–M8。图片 Provider 和经过对照验证的定价引擎属于 M5–M6。

## M4 使用流程

先调用 `product_capture_snapshot` 获取快照，再用该 `snapshotId` 调用 `product_optimize`，传入 `manualKeywords` 数组。完整流程顺序调用三次模型，返回分析、内容、SKU 三条生成记录 ID，以及待审核草稿。`product_preview_changes` 可读取持久化草稿。

分步操作时，先调用 `product_analyze`，将其 `generationId` 作为 `analysisId` 用于关键词和内容工具。SKU 翻译工具可独立生成草稿。

人工词与模型推荐词分别标记为 `manual`、`model_recommendation`，市场搜索量和增长率为 null。内容自动校验包含结构、引文、数字、长度、危险 HTML 文本和 SKU 身份。`STRUCTURE_VALIDATED` 表示这些检查已通过，事实语义与营销质量仍处于 `PENDING_REVIEW`。

描述由固定模板构造，原始图片 URL、数量与顺序保留。SKU 翻译按维度索引及原值 ID 匹配，并保留原有数组顺序。文本草稿保留原始重量、价格和库存，重量规则使用独立的重量草稿工具。

模型异常、空白、截断、无效结构或引文不匹配会留下失败生成记录。完整流程在所需阶段通过校验后创建草稿，失败结果可通过生成记录 ID 查看。重新生成会创建新的模型记录，原始快照保持不变。

## M5.1 图片提取

调用 `product_image_extract_assets`，参数为已有本地 `snapshotId`。清单按原始 URL 去重，保留商品图、规格值图、HTML 详情图的全部物理引用位置。SKU 图片来自 `skuPropertyList[].attrValueList[].imgUrl`，每条引用的 `skuMapKeys` 包含全部关联组合。

`totalAssets` 是去重资产数量，`totalReferences` 是非空 URL 位置数量；同一规格图片关联多个 SKU 仍只计一个位置。角色摘要按引用计数。各 sequence、数组及 HTML 图片索引从 0 开始，sourcePath 相对 `shopCollectItemInfo`。空 URL 计入 skippedEmptyReferences，非法 URL 保留引用并标为 INVALID_URL，调用方应先检查 validation。

商品图没有独立主图标记，因此保持 UNCLASSIFIED，索引 0 只作候选。URL 只做本地语法校验，不确认可达性；不下载、调用 Muse、上传、修改草稿或保存商品。非空的未验证 SKU 图片字段会明确报错，避免遗漏。完整字段约定见 [字段映射](temp-doc/M5/M5.1-field-mapping.md)。M5.2 尚未实施。

## 凭证

API 凭证从进程环境读取，数据库和日志不保存凭证。也可用 `MIAOSHOU_SECRET_ENCRYPTED` 搭配独立的 `MIAOSHOU_SECRET_KEY`。加密配置格式为 Base64 编码的 `12 字节随机 nonce + AES-256-GCM 密文与 16 字节认证标签`，密钥为 Base64 编码的 32 字节随机密钥。配置错误统一返回脱敏提示。

## 验证

```powershell
./scripts/build.ps1
```

测试包含独立 HMAC 黄金向量、请求字节一致性、错误脱敏、重量兜底、SKU 关联、快照及并发草稿，以及模型 JSON 模式、失败审计、来源引文、数字规格、HTML 图片保留、关键词来源和完整优化工作流。用合成数据执行自动化验证，实际商品快照、生成记录与 API 凭证留在本地。

设计来源与实现范围见 [需求](temp-doc/nxq.md)、[技术方案](temp-doc/technical-design.md)、[接口映射](temp-doc/api-mapping.md)、[验收计划](temp-doc/test-plan.md) 和 [初版验收记录](temp-doc/test-results.md)。

M4 的 [需求](temp-doc/M4-nxq.md)、[技术设计](temp-doc/M4-technical-design.md)、[任务拆分](temp-doc/M4-task.md) 和 [验收记录](temp-doc/M4-test-results.md) 记录本阶段决策与结果。
