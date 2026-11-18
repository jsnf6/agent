package org.javaup.ai.model;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record OrderInfo(
    String orderId,
    String productName,
    String status,
    String logisticsNo,
    String expectedDelivery,
    boolean canRefund
) {
}
