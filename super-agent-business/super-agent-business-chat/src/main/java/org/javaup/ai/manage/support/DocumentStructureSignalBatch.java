package org.javaup.ai.manage.support;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author:
 **/

public record DocumentStructureSignalBatch(
    List<String> contextLines,
    List<DocumentStructureSignal> signals
) {
}
