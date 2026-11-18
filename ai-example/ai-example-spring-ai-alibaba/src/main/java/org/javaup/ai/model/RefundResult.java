package org.javaup.ai.model;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record RefundResult(
    String orderId,
    boolean accepted,
    String refundNo,
    String message
) {
}
