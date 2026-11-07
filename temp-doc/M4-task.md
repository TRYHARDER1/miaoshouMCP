# M4 开发任务

| 顺序 | 交付 | 主要文件 | 验收 |
| --- | --- | --- | --- |
| M4.1 | Provider、配置、统一生成记录 | provider/llm、AiGenerationService、schema.sql | 模拟 HTTP、空白、截断、错误脱敏、JSON 结构 |
| M4.2 | 快照分析与来源校验 | ProductAnalysisService、内容 DTO、校验器 | 引文路径、数字、原始快照不变 |
| M4.3 | 关键词来源区分 | provider/keyword | manual/model_recommendation，市场指标 null |
| M4.4 | 标题及描述草稿 | ProductContentService、HTML 构造器 | 英文、25–255、10000、图片保留、HTML 安全 |
| M4.5 | SKU 翻译草稿 | ProductTranslationService | 完整覆盖、ID/Key/库存不变、拒绝非法输出 |
| M4.6 | 完整本地优化流程 | ProductOptimizationService、MCP 工具、测试 | 三类商品、生成追踪、重启读取、待审核状态 |

阶段按上述顺序实现并验证。实际模型验收需要本机 DeepSeek 凭证。测试报告区分自动结构校验和人工内容审核，并记录实际模型及 Prompt 版本。

M4.1–M4.6 已完成实现与测试，结果见 [M4 验收记录](M4-test-results.md)。
