package org.javaup.ai.model;

/**
 * @program:
 * @description: 模型对象
 * @author:
 **/
public record ProductInfo(
    String productId,
    String productName,
    String price,
    int stock,
    String highlights
) {
}
