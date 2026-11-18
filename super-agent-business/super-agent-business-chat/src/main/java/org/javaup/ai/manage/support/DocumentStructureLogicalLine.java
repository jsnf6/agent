package org.javaup.ai.manage.support;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author:
 **/

public record DocumentStructureLogicalLine(
    int lineNo,
    int sourceLineNo,
    int segmentIndex,
    int indentLevel,
    String rawText,
    String normalizedText
) {
}
