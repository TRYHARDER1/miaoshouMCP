# M5 技术设计

M5.1 使用 domain/image 中的不可变 record 表达资产、引用、清单及结构校验结果。一个资产支持多种角色，分类属于具体引用，避免同一 URL 同时用于商品图和详情图时丢失角色。角色摘要按物理引用计数，不是按去重资产计数。

ImageAssetExtractor 是纯本地组件，当前完成商品图与详情图分支，尚未注册 MCP 工具。使用 LinkedHashMap 按 URL 第一次出现顺序分组；引用独立保留全局 sequence，按它排序可以重建跨资产引用顺序。所有序号从 0 开始。

assetId = `IMG-` + hex(SHA256(snapshotId UTF-8 + NUL + originalUrl UTF-8))。sha256 字段为 null，留待下载阶段记录文件内容摘要；不混淆 URL 身份与文件内容哈希。

每个非法非空 URL 也保留资产和所有引用，使错误不会导致位置丢失；validation.valid=false，未来调度阶段必须拒绝 INVALID_URL。空值跳过且显式计数；非字符串及错误容器类型按来源路径报告，不强制转换为 URL。

计划中的服务入口应读取 SnapshotService.find/product，复用 ProductValidator 检查 SKU 组合，然后调用提取器；工具参数仅 snapshotId。SKU 实际字段适配确认之前，不注册不完整的 product_image_extract_assets。

本阶段不添加配置、外部客户端、网络调用、数据库表、缓存或草稿写入。M4 HtmlDescriptionBuilder 行为保持原样。
