# M4 技术设计

## Provider 与调用

新增 LlmProvider 抽象，输入为任务、版本化系统提示和商品数据，输出为实际模型、内容和结束原因。DeepSeekLlmProvider 实现官方 Chat Completions 协议，采用 json_object 响应格式，系统提示明确要求 JSON。

默认地址为 https://api.deepseek.com，模型按实施时官方文档选用 deepseek-flash，允许环境变量覆盖。请求设置超时及输出 token 上限。空内容、非 JSON、截断及 HTTP 错误分别处理，错误不回显凭证或原始 HTTP 错误正文。

未来 Muse 实现相同 Provider 契约，业务服务依赖接口。当前实现按同步 DeepSeek 调用设计，Muse 的同步或异步适配在实际接入时确定。

## 业务服务

- ProductAnalysisService 读取快照并生成可追溯分析，记录引用的字段路径与原文。
- KeywordProvider 独立于模型客户端。ManualKeywordProvider 合并人工关键词与分析中的模型推荐词，所有市场数值保持未知。
- ProductContentService 从结构化文本生成标题与固定模板描述。
- ProductTranslationService 按维度索引及原 attrValueId 匹配翻译，深拷贝原对象，仅替换展示文字。
- ProductOptimizationService 顺序组合上述能力，生成完整的本地草稿。
- AiGenerationService 统一保存每次生成与校验结果，供效果对照和排查使用。

## 校验

1. 严格解析结构化 DTO，拒绝多余字段、缺失字段与错误类型。
2. 检查来源引文确实存在于快照对应字段；生成的数字须可在产品事实中追溯。
3. 检查标题、英文文本、描述长度、图片引用和危险 HTML 文本。
4. SKU 翻译必须覆盖每个维度和规格值，未知、重复、遗漏或跨维度 ID 均失败。
5. 所有草稿经过原有 ProductValidator 校验，原始快照与受保护字段保持一致。

引用校验不能证明翻译或营销语义必然正确，因此输出始终等待人工审核。

## 持久化

在现有数据库上追加 ms_ai_generation。原有快照和重量草稿继续保留，表结构兼容现有数据库。M4 草稿采用生成记录 ID 作为规则 kind 的一部分，允许同一快照进行多轮效果比较。

生成记录先落库，再调用 Provider；成功后记录原始结构化输出与校验状态。内容输入摘要另行保留关键词来源和关联分析生成 ID，便于重启后追溯。失败记录保留输出或安全错误码。模型请求期间不持有长事务，草稿创建使用短事务。

## 官方资料

- [DeepSeek Chat Completions](https://api-docs.deepseek.com/api/create-chat-completion/)
- [DeepSeek JSON Output](https://api-docs.deepseek.com/guides/json_mode/)
