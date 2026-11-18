package org.javaup.ai.manage.service;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务层
 * @author:
 **/

public interface DocumentAsyncProcessService {

    void handleParseRoute(Long documentId, Long taskId);

    void handleIndexBuild(Long documentId, Long taskId, Long planId);
}
