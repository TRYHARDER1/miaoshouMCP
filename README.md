# 妙手商品编辑 MCP

Java 21、Spring Boot 3.5.16、Spring AI 1.1.8、MyBatis-Plus 与 SQLite 的本地 STDIO 服务。当前版本为 `0.1.0`。

当前实现包含 M0–M3 基础、重量草稿以及 M4 DeepSeek 文本优化。里程碑与阶段边界见 [开发任务](temp-doc/task.md)。

M5.1 已完成图片资产提取与引用映射，并按用户确认适配真实 SKU 字段。商品图暂为 UNCLASSIFIED，首图仅标记主图候选。进度见 [M5 任务](temp-doc/M5/M5-task.md)，阶段结果见 [M5.1 测试报告](temp-doc/M5/M5.1-test-report.md)。

M5.2–M5.3 已完成批次和人工交接。安全修正新增独立白名单交付副本，见 [安全修正报告](temp-doc/M5/M5.3-security-fix-report.md)。M5.4 已实现已登记结果的安全下载、技术完整性校验和本地恢复，见 [M5.4 技术测试报告](temp-doc/M5/M5.4-test-report.md)；真实 Muse 输出验收待补，不涉及视觉质量审核或上传。

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
| `product_image_create_batches` | 创建或复用快照唯一批次计划，batchSize 默认 4、允许 1–4 |
| `product_image_get_batches` | 按 snapshotId 或 batchPlanId 读取完整持久化批次 |
| `product_image_get_batch_progress` | 查询已分配、各状态、剩余及可领取数量 |
| `product_image_pause_batch` / `product_image_resume_batch` | 暂停或恢复指定 batchId |
| `product_image_pause_batches` / `product_image_resume_batches` | 原子暂停或恢复一个快照计划的剩余批次 |
| `product_image_cancel_batch` | 永久取消指定批次，保留完成结果，跳过其余任务 |
| `product_image_retry_batch` | 只重排原批次的 FAILED 任务，不重复分配 |
| `product_image_recover_batches` | 回收过期尝试并读取原计划，不自动重试 |
| `product_image_export_batch` | 从已有 batchId 导出实际原图、完整 manifest 和英文 Prompt |
| `product_image_mark_handed_off` | 明确记录人工交接，不远程提交 Muse |
| `product_image_submit_results` | 按 assetId 原子登记 1–4 条下载链接，允许部分提交 |
| `product_image_get_batch_results` | 查看持久化交接状态、原链接及尚缺资产，修复链接文件投影 |
| `product_image_export_muse_delivery` | 从内部包生成外发白名单副本，去除 URL、SKU Key 和 references |
| `product_image_download_results` | 仅获取数据库已登记的结果，按资产独立校验与保存 |
| `product_image_get_processed_results` | 查询独立处理状态，可本地核对文件；不自动联网 |
| `product_image_get_download_progress` | 查询登记/缺链接/各处理状态统计，不等于最新文件健康保证 |
| `product_image_retry_download` | expectedRowVersion 约束下明确重试暂时失败，不换链接或覆盖成品 |
| `product_image_recover_downloads` | 从完整本地阶段或发布凭据恢复，不启动网络下载 |

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

商品图没有独立主图标记，因此保持 UNCLASSIFIED，索引 0 只作候选。URL 只做本地语法校验，不确认可达性；不下载、调用 Muse、上传、修改草稿或保存商品。非空的未验证 SKU 图片字段会明确报错，避免遗漏。完整字段约定见 [字段映射](temp-doc/M5/M5.1-field-mapping.md)。

## M5.2 批次调度

先调用 `product_image_create_batches`，传入已有 `snapshotId` 和可选 `batchSize`。同一快照重复或并发调用返回原计划；省略大小时复用已有配置，显式指定不同大小会返回 BATCH_SIZE_CONFLICT。清单验证失败时整笔拒绝，不生成部分计划。空清单返回持久化的空计划。总工具数为 24。

查询工具接受 snapshotId、batchPlanId 至少一个，两者同时传入时必须一致。结果中的 batches[].tasks[].task 是持久化任务状态，asset 是完整 M5.1 资产及全部引用。totalSkipped 统计跳过的资产任务，空图片引用不计入任务。remaining=pending+processing+failed；pending 包含暂停中的待处理任务，eligiblePending 是当前允许领取的数量。

状态落在 SQLite 新增三表中，原快照和草稿不变。暂停保留进度；恢复复用原任务；取消不能撤销，会将未完成任务标为 SKIPPED。失败重试仅更新原 FAILED 行及 retryCount，已经完成的资产不会重新入队。

本阶段不派发图片。内部领取/心跳/结果服务使用尝试 token 和租期，默认 1800 秒，可用 Spring 属性 `image-batch.lease-seconds` 设置为 1–86400。启动及查询/控制操作将过期 PROCESSING 标为 FAILED/LEASE_EXPIRED，未过期状态保留。异常恢复不会自动重试未来的外部处理；应先核对外部结果，再明确调用重试。COMPLETED 仅是调度状态，不代表 Muse 已优化或图片质量合格。

## M5.3 Muse 人工交接

1. 从已有计划选择 batchId，调用 `product_image_export_batch`。导出时只获取该批次的快照原图，最多四张；返回 jobId、manifest、prompt、exportPath 和交接状态。同批次重复导出复用版本 1，其他 version 返回冲突。
2. 旧 exportPath 是内部管理包，含原 URL 和 SKU 关联。调用 `product_image_export_muse_delivery(batchId)`，只把它返回的独立交付目录中的图片、manifest.json 和 prompt.md 交给 Muse。新目录不包含源 URL、SKU Key、references、内部业务 ID 或结果链接。图片原字节保留，未额外清理图像嵌入元数据。
3. 人工交接后可调用 `product_image_mark_handed_off`；这仅记录 WAITING_MUSE，不占用 M5.2 租期。
4. 调用 `product_image_submit_results`，传入 batchId 与 results 数组，每项包含 assetId、downloadUrl，可分次提交。相同链接重复提交幂等，不同链接或同 URL 映射多资产会冲突。未收到的资产保留在 missingAssetIds。
5. 用 `product_image_get_batch_results` 查询登记。LINK_REGISTERED、downloadVerified=false 只表示链接已保存，当前不下载结果，不表示质量合格或上传完成。

任务包默认位于 `.data/image-jobs/JOB-{jobId}/`，根目录由 Spring 属性 `muse.jobs-root` 配置。安全原图获取仅允许公网 HTTPS/443，逐跳核对重定向/DNS/IP，连接固定已核对 IP，保留 TLS 校验；每图最多 8 MiB，只支持实际解码通过的 JPEG/PNG。单个元数据/Prompt 文件最多 2 MiB。源图为 HTTP、其他格式、不可达或超限时明确失败，不缺图交接。

人工交接状态独立持久化为 EXPORTED、WAITING_MUSE、PARTIALLY_RECEIVED、LINKS_RECEIVED；导出预约使用 CREATING，失败为 EXPORT_FAILED。活动导出无完整目录时重复请求返回忙；失败/超时后可重试，已发布目录可恢复，文件损坏不会被静默覆盖。结果以数据库为准，result-links.json 写入失败时返回 SYNC_PENDING，后续查询可修复。失败时可能留下私有 `.staging` 目录，程序不自动删除。

当前共 34 个工具。暂停、取消、处理中批次禁止新的交接操作；历史链接仍可查询。没有 Muse API/网页自动化、图片上传、商品图片草稿修改、保存或发布。结果下载只由 M5.4 的明确工具请求触发。

安全交付默认在 `.data/muse-deliveries/DELIVERY-{deliveryId}/`，可用 Spring 属性 `muse.delivery-root` 指定私有根目录。它是同一内部 job 的独立策略版本投影，旧 jobVersion=1、旧 Prompt、包及所有关联保持。不要把整个内部 JOB 目录作为外发目录。

## M5.4 结果文件与技术校验

链接先通过 M5.3 登记，再调用 `product_image_download_results(batchId, assetIds?)`。省略列表只处理已登记资产；空数组、跨批资产拒绝。结果是逐资产的状态/文件元数据，部分失败不回滚相邻成功。不接受任意 URL/path/force 参数。

只支持公网 HTTPS/443 的 JPEG/PNG，每图最多 8 MiB、4000 万像素、单边 20000。逐跳核对 DNS/IP 并固定连接、TLS 证书/主机名校验，关闭代理和自动重试；传输字节、超时及工作容量有界。PNG CRC/结构、JPEG 终止标记、真实解码和 warning 都参与检查，不凭扩展名判断。每进程最多两个实际工作位，数据库也限制两个有效活跃租期；这不意味着能强杀其他进程已过期的解码线程。

默认目录在内部包的 `processed/A1`–`A4`，短父目录对应冻结批内顺序以兼容 Windows；图片文件名和 integrity.json 均保留完整 assetId。原图、旧 job.json/manifest.json/prompt.md 不改。技术状态和事件只写新增 ms_muse_processed_result/event，不更新 M5.3 downloadVerified；旧字段仍表示登记阶段未做验证。技术通过的新视图为 integrityVerified=true、visualReviewStatus=NOT_REVIEWED。

`get_processed_results` 默认核对本地摘要；verifyLocal=false 时 fileHealth=UNKNOWN、integrityVerified=false。进度只反映持久化状态。损坏/缺失成品会登记明确失败，不自动覆盖；相同内容的不同资产独立保存并提示 duplicateContent。签名链接过期不自动换签，403/404 等明确阻断。

有效结果重复请求不重新下载。中断后用 `recover_downloads` 重用完整本地阶段或发布凭据，新 owner 使用新 token；没有完整数据时明确重试，从头获取，不拼接半文件。普通 download 不自动重排 FAILED；暂时失败用 retry_download 指定查询到的 rowVersion，最多三次网络重试。源码登记 URL、原图和成品冲突不能用该工具覆盖。

升级首次添加 M5.4 两表之前，会在旧库同目录的 `.backups` 使用 SQLite 一致备份；失败则停止初始化。两表和索引原子创建，不重建旧表；不兼容结构明确拒绝。备份/原图/结果/临时目录都应保持私有。失败和成功阶段可能保留 staging 供恢复诊断，程序不自动删除。

当前已完成代码与技术夹具验收；真实 Muse 输出验收按用户选择待补。公开图片传输夹具不代表 Muse 已优化，也不构成视觉质量合格。没有进入 M5.5。

## 凭证

API 凭证从进程环境读取，数据库和日志不保存凭证。也可用 `MIAOSHOU_SECRET_ENCRYPTED` 搭配独立的 `MIAOSHOU_SECRET_KEY`。加密配置格式为 Base64 编码的 `12 字节随机 nonce + AES-256-GCM 密文与 16 字节认证标签`，密钥为 Base64 编码的 32 字节随机密钥。配置错误统一返回脱敏提示。

## 验证

```powershell
./scripts/build.ps1
```

测试包含独立 HMAC 黄金向量、请求字节一致性、错误脱敏、重量兜底、SKU 关联、快照及并发草稿，以及模型 JSON 模式、失败审计、来源引文、数字规格、HTML 图片保留、关键词来源和完整优化工作流。用合成数据执行自动化验证，实际商品快照、生成记录与 API 凭证留在本地。

设计来源与实现范围见 [需求](temp-doc/nxq.md)、[技术方案](temp-doc/technical-design.md)、[接口映射](temp-doc/api-mapping.md)、[验收计划](temp-doc/test-plan.md) 和 [初版验收记录](temp-doc/test-results.md)。

M4 的 [需求](temp-doc/M4-nxq.md)、[技术设计](temp-doc/M4-technical-design.md)、[任务拆分](temp-doc/M4-task.md) 和 [验收记录](temp-doc/M4-test-results.md) 记录本阶段决策与结果。
