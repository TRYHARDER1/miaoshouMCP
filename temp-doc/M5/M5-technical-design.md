# M5 技术设计

M5.1 使用 domain/image 中的不可变 record 表达资产、引用、清单及结构校验结果。一个资产支持多种角色，分类属于具体引用，避免同一 URL 同时用于商品图和详情图时丢失角色。角色摘要按物理引用计数，不是按去重资产计数。

ImageAssetExtractor 是纯本地组件，按商品图、规格值图、详情图顺序提取。使用 LinkedHashMap 按 URL 第一次出现顺序分组；引用独立保留全局 sequence，按它排序可以重建跨资产引用顺序。所有序号从 0 开始。

assetId = `IMG-` + hex(SHA256(snapshotId UTF-8 + NUL + originalUrl UTF-8))。sha256 字段为 null，留待下载阶段记录文件内容摘要；不混淆 URL 身份与文件内容哈希。

每个非法非空 URL 也保留资产和所有引用，使错误不会导致位置丢失；validation.valid=false，未来调度阶段必须拒绝 INVALID_URL。空值跳过且显式计数；非字符串及错误容器类型按来源路径报告，不强制转换为 URL。

ProductImageService 读取 SnapshotService.find/product，检查快照身份，复用 ProductValidator 检查 SKU 组合，然后调用提取器。ProductImageTools 注册 product_image_extract_assets，参数仅 snapshotId，现有 MCP 工具总数为 14。未知快照、空标识、身份损坏及 SKU 关系损坏使用 BusinessException 阻断。

SKU 图片读取用户确认的实际规格值字段，通过精确 ID token 匹配原 SKU Map Key，支持 Key 内维度顺序变化。每个物理图片位置返回所有原始 skuMapKeys，多个位置即使共享 URL 也不会合并引用。无关联组合的图片保留引用并发出 SKU_IMAGE_WITHOUT_SKU 警告。非空的未验证图片字段产生明确错误，避免静默遗漏。

本阶段不添加配置、外部客户端、网络调用、数据库表、缓存或草稿写入。M4 HtmlDescriptionBuilder 行为保持原样。

## M5.2 扩展

M5.1 保持纯提取；M5.2 新增三张调度表和 ImageBatchService/MyBatis Mapper。计划唯一性、任务唯一性、事务原子性、完整清单摘要、租期与尝试 token 共同保证恢复时不重建任务。详细状态机、DDL 位置及恢复规则见 [M5.2 技术设计](M5.2-technical-design.md)。没有执行器或外部图片处理。

## M5.3 扩展

新增独立 job/result 表，人工交接不领取 M5.2 任务；只读批次上下文，安全获取快照原图，导出固定版本包及 Prompt，按 assetId 登记链接但不下载结果。文件系统和数据库一致性采用预约 token、原子目录发布及可重建链接投影。安全限制、版本/恢复语义见 [M5.3 技术设计](M5.3-technical-design.md)。
