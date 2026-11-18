package org.javaup.memory.model;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record ComparisonTurnResponse(
    int round,
    String question,
    String noMemoryAnswer,
    String slidingWindowAnswer,
    String summaryAnswer,
    String summarySnapshot,
    int summaryCompressionCount
) {
}
