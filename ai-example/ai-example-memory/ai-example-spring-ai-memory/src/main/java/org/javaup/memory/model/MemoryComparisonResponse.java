package org.javaup.memory.model;

import java.util.List;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record MemoryComparisonResponse(
    String scriptName,
    List<ComparisonTurnResponse> turns,
    MemoryChatResponse slidingWindowFinalState,
    MemoryChatResponse summaryFinalState
) {
}
