package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.service.docling.DoclingServeClient;
import org.javaup.ai.manage.support.DocumentAnalysisAssembler;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.javaup.ai.manage.support.DocumentStructureNodeExtractor;
import org.javaup.ai.manage.support.DoclingDocumentStructureMapper;
import org.javaup.enums.DocumentFileTypeEnum;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@Slf4j
@Primary
@Service
@AllArgsConstructor
@ConditionalOnProperty(prefix = "app.manage.structure-parsing", name = "provider",
    havingValue = "docling", matchIfMissing = true)
public class DoclingDocumentParserService implements DocumentParserService {

    private final DoclingServeClient doclingClient;
    private final DoclingDocumentStructureMapper structureMapper;
    private final DocumentStructureNodeExtractor structureNodeExtractor;
    private final DocumentAnalysisAssembler analysisAssembler;
    private final TikaDocumentParserService tikaParser;
    private final DocumentManageProperties properties;

    @Override
    public DocumentAnalysisResult parse(byte[] bytes,
                                        String originalFileName,
                                        String mimeType,
                                        DocumentFileTypeEnum fileType) {
        if (!supportsDocling(originalFileName, fileType)) {
            return tikaParser.parse(bytes, originalFileName, mimeType, fileType);
        }
        try {
            DoclingServeClient.DoclingConversionResult conversion = doclingClient.convert(bytes, originalFileName, mimeType);
            String exportedMarkdown = StrUtil.blankToDefault(conversion.markdown(), "").trim();
            String textContent = StrUtil.blankToDefault(conversion.text(), "").trim();
            if (StrUtil.isBlank(exportedMarkdown) && StrUtil.isBlank(textContent) && conversion.doclingDocument() == null) {
                throw new IllegalStateException("Docling 未返回可解析内容");
            }

            List<DocumentStructureNodeCandidate> structureNodes;
            String markdown;
            if (conversion.doclingDocument() != null) {
                try {
                    DoclingDocumentStructureMapper.DoclingStructureMapping mapping = structureMapper.map(
                        originalFileName,
                        conversion.doclingDocument(),
                        exportedMarkdown
                    );
                    markdown = mapping.markdown();
                    structureNodes = mapping.structureNodes();
                }
                catch (Exception mappingException) {
                    log.warn("Docling 文档结构映射失败，回退到文本结构识别: {}", mappingException.getMessage());
                    markdown = StrUtil.blankToDefault(exportedMarkdown, textContent).trim();
                    structureNodes = structureNodeExtractor.extract(originalFileName, markdown);
                }
            }
            else {
                log.warn("Docling 未返回 DoclingDocument JSON，使用文本结构识别补全结构节点");
                markdown = StrUtil.blankToDefault(exportedMarkdown, textContent).trim();
                structureNodes = structureNodeExtractor.extract(originalFileName, markdown);
            }
            markdown = StrUtil.blankToDefault(markdown, textContent).trim();
            return analysisAssembler.assemble(markdown, structureNodes);
        }
        catch (Exception exception) {
            if (!fallbackToTika()) {
                throw new IllegalStateException("Docling 解析失败: " + messageOf(exception), exception);
            }
            log.warn("Docling 解析失败，回退到 Tika: {}", messageOf(exception));
            return tikaParser.parse(bytes, originalFileName, mimeType, fileType);
        }
    }

    private boolean supportsDocling(String originalFileName, DocumentFileTypeEnum fileType) {
        DocumentManageProperties.Docling docling = properties.getStructureParsing().getDocling();
        List<String> fileTypes = docling.getFileTypes();
        if (fileTypes == null || fileTypes.isEmpty()) {
            return true;
        }
        String suffix = resolveSuffix(originalFileName, fileType);
        if (StrUtil.isBlank(suffix)) {
            return false;
        }
        return fileTypes.stream()
            .filter(StrUtil::isNotBlank)
            .anyMatch(item -> item.trim().equalsIgnoreCase(suffix));
    }

    private String resolveSuffix(String originalFileName, DocumentFileTypeEnum fileType) {
        String fileName = StrUtil.blankToDefault(originalFileName, "").trim();
        int index = fileName.lastIndexOf('.');
        if (index >= 0 && index < fileName.length() - 1) {
            return fileName.substring(index + 1).toLowerCase();
        }
        if (fileType == null) {
            return "";
        }
        return switch (fileType) {
            case PDF -> "pdf";
            case DOC -> "doc";
            case DOCX -> "docx";
            case TXT -> "txt";
            case MD -> "md";
            case HTML -> "html";
        };
    }

    private boolean fallbackToTika() {
        return !Boolean.FALSE.equals(properties.getStructureParsing().getDocling().getFallbackToTika());
    }

    private String messageOf(Exception exception) {
        return StrUtil.blankToDefault(exception.getMessage(), exception.getClass().getSimpleName());
    }
}
