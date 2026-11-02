package org.javaup.ai.manage.support;

import java.util.List;

/**
 * 文档知识与人工维护 scope/topic 的自动绑定结果。
 */
public record DocumentKnowledgeBindingResult(
    Long documentId,
    String scopeCode,
    String scopeName,
    double scopeScore,
    double scopeConfidence,
    boolean scopeAutoSelected,
    List<TopicBinding> topics,
    String reason
) {

    public static DocumentKnowledgeBindingResult skipped(Long documentId, String reason) {
        return new DocumentKnowledgeBindingResult(documentId, null, null, 0D, 0D, false, List.of(), reason);
    }

    public record TopicBinding(
        String topicCode,
        String topicName,
        double score,
        String reason
    ) {
    }
}
