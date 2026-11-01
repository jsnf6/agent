package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import lombok.AllArgsConstructor;
import org.javaup.enums.DocumentContentQualityLevelEnum;
import org.javaup.enums.DocumentStructureLevelEnum;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

@AllArgsConstructor
@Component
public class DocumentAnalysisAssembler {

    private final DocumentLineClassifier documentLineClassifier;

    public DocumentAnalysisResult assemble(String parsedText,
                                           List<DocumentStructureNodeCandidate> structureNodes) {
        String text = StrUtil.blankToDefault(parsedText, "").trim();
        List<DocumentStructureNodeCandidate> safeNodes = structureNodes == null ? List.of() : structureNodes;
        List<String> paragraphList = extractParagraphs(text);
        int maxParagraphLength = paragraphList.stream().mapToInt(String::length).max().orElse(0);
        int charCount = text.length();
        int headingCount = countHeadings(text, safeNodes);
        int structureLevel = evaluateStructureLevel(headingCount, paragraphList.size());
        int contentQualityLevel = evaluateContentQuality(text, charCount);
        return new DocumentAnalysisResult(
            text,
            charCount,
            estimateTokenCount(text),
            structureLevel,
            contentQualityLevel,
            headingCount,
            paragraphList.size(),
            maxParagraphLength,
            new ArrayList<>(safeNodes)
        );
    }

    private int countHeadings(String text,
                              List<DocumentStructureNodeCandidate> structureNodes) {
        long structuredHeadingCount = structureNodes.stream()
            .filter(node -> node != null
                && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType())
                && node.getDepth() != null
                && node.getDepth() > 0)
            .count();
        if (structuredHeadingCount > 0) {
            return (int) structuredHeadingCount;
        }
        int count = 0;
        for (String line : text.split("\n")) {
            if (documentLineClassifier.classify(line).isHeading()) {
                count++;
            }
        }
        return count;
    }

    private List<String> extractParagraphs(String text) {
        List<String> paragraphList = new ArrayList<>();
        for (String paragraph : text.split("\\n\\s*\\n")) {
            String trimmed = paragraph.trim();
            if (StrUtil.isNotBlank(trimmed)) {
                paragraphList.add(trimmed);
            }
        }
        return paragraphList;
    }

    private int estimateTokenCount(String text) {
        int englishWordCount = 0;
        int chineseCharCount = 0;

        for (String word : text.split("\\s+")) {
            if (word.matches(".*[A-Za-z].*")) {
                englishWordCount++;
            }
        }

        for (char current : text.toCharArray()) {
            if (String.valueOf(current).matches("[\\u4e00-\\u9fa5]")) {
                chineseCharCount++;
            }
        }

        return englishWordCount + chineseCharCount + Math.max(1, (text.length() - chineseCharCount) / 4);
    }

    private int evaluateStructureLevel(int headingCount, int paragraphCount) {
        if (headingCount >= 5) {
            return DocumentStructureLevelEnum.HIGH.getCode();
        }
        if (headingCount >= 2) {
            return DocumentStructureLevelEnum.MEDIUM.getCode();
        }
        if (paragraphCount >= 3) {
            return DocumentStructureLevelEnum.LOW.getCode();
        }
        return DocumentStructureLevelEnum.UNKNOWN.getCode();
    }

    private int evaluateContentQuality(String text, int charCount) {
        if (StrUtil.isBlank(text) || charCount < 20) {
            return DocumentContentQualityLevelEnum.LOW.getCode();
        }

        long brokenCharCount = text.chars().filter(value -> value == '\uFFFD').count();
        double brokenRatio = charCount == 0 ? 1D : (double) brokenCharCount / (double) charCount;
        if (brokenRatio > 0.02D || charCount < 100) {
            return DocumentContentQualityLevelEnum.LOW.getCode();
        }
        if (brokenRatio > 0.005D || charCount < 500) {
            return DocumentContentQualityLevelEnum.MEDIUM.getCode();
        }
        return DocumentContentQualityLevelEnum.HIGH.getCode();
    }
}
