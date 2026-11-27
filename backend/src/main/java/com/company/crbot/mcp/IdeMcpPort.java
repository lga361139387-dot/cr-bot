package com.company.crbot.mcp;

import com.fasterxml.jackson.databind.JsonNode;

/**
 * IDE 侧只读能力：评审记录与知识库检索。
 * 不提供重跑、作废、改知识库，避免外部 Agent 改 CRBOT 数据。
 */
public interface IdeMcpPort {

    /**
     * purpose：按仓库、分支、提交筛选评审任务。
     * business-logic：默认排除已作废任务，便于对上刚 push 的那一次评审。
     * params：args 为 MCP 工具参数。
     * return：JSON 文本。
     * exception：参数不合法时抛 ApiException。
     */
    String listReviews(JsonNode args);

    /**
     * purpose：读取一次评审的摘要和缺陷列表。
     * business-logic：带行号、建议和人工标注，供本地改代码时取舍。
     * params：args 必须含 jobId。
     * return：JSON 文本。
     * exception：任务不存在时抛 ApiException。
     */
    String getReview(JsonNode args);

    /**
     * purpose：在已索引知识库里做混合检索。
     * business-logic：只查 INDEXED 文档，分数原样返回，由调用方判断够不够用。
     * params：args 必须含 query。
     * return：JSON 文本。
     * exception：参数不合法或向量服务失败时抛出。
     */
    String searchKnowledge(JsonNode args);
}
