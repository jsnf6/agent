package org.javaup.ai.manage.service;

import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentKnowledgeBindingResult;

import java.util.List;

/**
 * 将导入文档绑定到已人工维护的 scope/topic，不自动创建分类节点。
 */
public interface DocumentKnowledgeBindingService {

    DocumentKnowledgeBindingResult bind(Long documentId,
                                        DocumentAnalysisResult analysisResult,
                                        List<SuperAgentDocumentStructureNode> structureNodes);
}
