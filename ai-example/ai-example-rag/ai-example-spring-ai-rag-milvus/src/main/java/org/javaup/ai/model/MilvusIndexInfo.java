package org.javaup.ai.model;

import java.util.Map;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record MilvusIndexInfo(
        String fieldName,
        String indexName,
        String state,
        long indexedRows,
        long totalRows,
        Map<String, String> params
) {
}
