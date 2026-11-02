package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 根据标题、章节标题和正文信号判断文档主类型。
 */
public final class DocumentTypeClassifier {

    public static final String FAQ = "faq";
    public static final String TROUBLESHOOTING = "troubleshooting";
    public static final String RULE = "rule";
    public static final String SPEC = "spec";
    public static final String MANUAL = "manual";
    public static final String INTRO = "intro";

    private static final int MIN_HEADING_HITS = 2;
    private static final int MIN_BODY_HITS = 4;
    private static final double DOMINANCE_RATIO = 1.3D;

    private static final List<TypeRule> TITLE_RULES = List.of(
        new TypeRule(FAQ, List.of("常见问题", "问题清单", "问题解答", "问答", "faq", "q&a")),
        new TypeRule(TROUBLESHOOTING, List.of("故障", "排查", "排障", "报错", "异常处理", "应急", "事故", "维修", "问题定位", "诊断")),
        new TypeRule(SPEC, List.of("技术规格", "规格参数", "参数表", "技术指标", "性能指标", "产品规格", "datasheet")),
        new TypeRule(RULE, List.of("管理制度", "管理办法", "办法", "条例", "政策", "准则")),
        new TypeRule(MANUAL, List.of("操作规范", "操作手册", "使用手册", "用户手册", "产品手册", "培训手册", "手册", "指南",
            "教程", "操作说明", "使用说明", "部署指南", "说明书", "培训")),
        new TypeRule(RULE, List.of("制度", "规则", "规范"))
    );

    private static final Map<String, List<String>> TYPE_TERMS = Map.of(
        FAQ, List.of("常见问题", "问题清单", "问题解答", "问答", "faq", "q&a"),
        TROUBLESHOOTING, List.of("故障", "排查", "排障", "报错", "异常处理", "应急", "事故", "维修", "问题定位", "诊断"),
        RULE, List.of("管理制度", "管理办法", "办法", "条例", "政策", "准则", "制度", "规则", "规范"),
        SPEC, List.of("技术规格", "规格参数", "参数表", "技术指标", "性能指标", "产品规格", "datasheet"),
        MANUAL, List.of("操作规范", "操作手册", "使用手册", "用户手册", "产品手册", "培训手册", "手册", "指南", "教程",
            "操作说明", "使用说明", "部署指南", "说明书", "培训", "操作步骤")
    );

    private DocumentTypeClassifier() {
    }

    public static String classify(String documentName,
                                  String originalFileName,
                                  List<String> sectionTitles,
                                  String parsedText,
                                  boolean supportsItemLookup) {
        String titleText = normalize(StrUtil.blankToDefault(documentName, "") + " "
            + StrUtil.blankToDefault(originalFileName, ""));
        String titleType = matchTitleType(titleText);
        if (titleType != null) {
            return titleType;
        }

        List<String> safeSectionTitles = sectionTitles == null ? List.of() : sectionTitles;
        String headingType = pickDominant(scoreHeadings(safeSectionTitles), MIN_HEADING_HITS);
        if (headingType != null) {
            return headingType;
        }

        if (supportsItemLookup) {
            return MANUAL;
        }

        String bodyType = pickDominant(scoreOccurrences(parsedText), MIN_BODY_HITS);
        return bodyType == null ? INTRO : bodyType;
    }

    private static String matchTitleType(String titleText) {
        if (StrUtil.isBlank(titleText)) {
            return null;
        }
        for (TypeRule rule : TITLE_RULES) {
            if (rule.terms().stream().anyMatch(titleText::contains)) {
                return rule.type();
            }
        }
        return null;
    }

    private static Map<String, Integer> scoreHeadings(List<String> sectionTitles) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        for (String title : sectionTitles) {
            String normalized = normalize(title);
            if (StrUtil.isBlank(normalized)) {
                continue;
            }
            TYPE_TERMS.forEach((type, terms) -> {
                if (terms.stream().anyMatch(normalized::contains)) {
                    scores.merge(type, 1, Integer::sum);
                }
            });
        }
        return scores;
    }

    private static Map<String, Integer> scoreOccurrences(String text) {
        Map<String, Integer> scores = new LinkedHashMap<>();
        String normalized = normalize(text);
        if (StrUtil.isBlank(normalized)) {
            return scores;
        }
        TYPE_TERMS.forEach((type, terms) -> {
            int hits = terms.stream()
                .mapToInt(term -> countOccurrences(normalized, normalize(term)))
                .sum();
            if (hits > 0) {
                scores.put(type, hits);
            }
        });
        return scores;
    }

    private static String pickDominant(Map<String, Integer> scores, int minHits) {
        if (scores == null || scores.isEmpty()) {
            return null;
        }
        List<Map.Entry<String, Integer>> ranked = new ArrayList<>(scores.entrySet());
        ranked.sort(Map.Entry.<String, Integer>comparingByValue().reversed());
        int topHits = ranked.get(0).getValue();
        int secondHits = ranked.size() > 1 ? ranked.get(1).getValue() : 0;
        if (topHits < minHits || (secondHits > 0 && topHits < secondHits * DOMINANCE_RATIO)) {
            return null;
        }
        return ranked.get(0).getKey();
    }

    private static int countOccurrences(String source, String term) {
        if (StrUtil.isBlank(source) || StrUtil.isBlank(term)) {
            return 0;
        }
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private static String normalize(String text) {
        return StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT);
    }

    private record TypeRule(String type, List<String> terms) {
    }
}
