package org.javaup.ai.model;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record OrderSummary(
    String orderId,
    String status,
    boolean canRefund,
    String nextAction
) {
}
