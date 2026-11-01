package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import org.apache.tika.Tika;
import org.javaup.ai.manage.service.DocumentParserService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentAnalysisAssembler;
import org.javaup.ai.manage.support.DocumentStructureNodeCandidate;
import org.javaup.ai.manage.support.DocumentStructureNodeExtractor;
import org.javaup.enums.DocumentFileTypeEnum;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Service
public class TikaDocumentParserService implements DocumentParserService {

    private static final Tika TIKA = new Tika();
    private final DocumentStructureNodeExtractor structureNodeExtractor;
    private final DocumentAnalysisAssembler analysisAssembler;

    @Override

    public DocumentAnalysisResult parse(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {

        String rawText = extractRawText(bytes, originalFileName, mimeType, fileType);

        String cleanedText = cleanupText(rawText);

        List<DocumentStructureNodeCandidate> structureNodes = structureNodeExtractor.extract(originalFileName, cleanedText);
        return analysisAssembler.assemble(cleanedText, structureNodes);
    }

    private String extractRawText(byte[] bytes, String originalFileName, String mimeType, DocumentFileTypeEnum fileType) {
        try {
            if (fileType == DocumentFileTypeEnum.PDF || fileType == DocumentFileTypeEnum.DOC || fileType == DocumentFileTypeEnum.DOCX) {

                return TIKA.parseToString(new ByteArrayInputStream(bytes));
            }
            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD) {

                return new String(bytes, StandardCharsets.UTF_8);
            }
            if (fileType == DocumentFileTypeEnum.HTML) {

                return TIKA.parseToString(new ByteArrayInputStream(bytes));
            }

            return TIKA.parseToString(new ByteArrayInputStream(bytes));
        }
        catch (Exception exception) {

            if (fileType == DocumentFileTypeEnum.TXT || fileType == DocumentFileTypeEnum.MD || mimeType != null && mimeType.startsWith("text/")) {
                return new String(bytes, StandardCharsets.UTF_8);
            }
            throw new IllegalStateException("Tika 解析失败: " + exception.getMessage(), exception);
        }
    }

    private String cleanupText(String rawText) {
        if (StrUtil.isBlank(rawText)) {
            return "";
        }

        String cleaned = rawText
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .replaceAll("[\\t\\x0B\\f]+", " ")
            .replaceAll("\\n{3,}", "\n\n")
            .replaceAll("[ ]{2,}", " ")
            .trim();
        return cleaned;
    }

}
