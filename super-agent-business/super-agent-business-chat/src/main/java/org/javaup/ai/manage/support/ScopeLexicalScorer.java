package org.javaup.ai.manage.support;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 计算文档标题、章节和摘要中的 scope 关键词证据。
 */
public final class ScopeLexicalScorer {

    private static final int MAX_TERMS = 240;
    private static final double SATURATION = 1.15D;
    private static final double TITLE_STRONG_EVIDENCE = 0.60D;
    private static final double HEADING_STRONG_EVIDENCE = 1.00D;

    private static final Map<String, Double> COMMON_TERM_WEIGHTS = Map.ofEntries(
        Map.entry("管理", 0.20D),
        Map.entry("制度", 0.20D),
        Map.entry("规范", 0.20D),
        Map.entry("规则", 0.25D),
        Map.entry("流程", 0.25D),
        Map.entry("中心", 0.20D),
        Map.entry("部门", 0.20D),
        Map.entry("公司", 0.18D),
        Map.entry("员工", 0.65D),
        Map.entry("人员", 0.25D),
        Map.entry("相关", 0.15D),
        Map.entry("知识", 0.15D),
        Map.entry("内容", 0.15D),
        Map.entry("说明", 0.15D),
        Map.entry("指南", 0.20D),
        Map.entry("手册", 0.20D),
        Map.entry("操作", 0.25D),
        Map.entry("使用", 0.20D),
        Map.entry("支持", 0.20D),
        Map.entry("系统", 0.22D),
        Map.entry("服务", 0.20D),
        Map.entry("工作", 0.20D),
        Map.entry("业务", 0.20D),
        Map.entry("基础", 0.20D),
        Map.entry("信息", 0.20D),
        Map.entry("事项", 0.15D),
        Map.entry("问题", 0.15D),
        Map.entry("方案", 0.18D),
        Map.entry("方法", 0.18D),
        Map.entry("场景", 0.18D),
        Map.entry("情况", 0.18D),
        Map.entry("进行", 0.12D),
        Map.entry("以及", 0.12D),
        Map.entry("其他", 0.12D),
        Map.entry("要求", 0.20D),
        Map.entry("标准", 0.22D),
        Map.entry("处理", 0.20D),
        Map.entry("异常", 0.30D),
        Map.entry("常见", 0.18D),
        Map.entry("一般", 0.15D),
        Map.entry("提供", 0.15D),
        Map.entry("包括", 0.15D),
        Map.entry("涵盖", 0.15D),
        Map.entry("涉及", 0.15D),
        Map.entry("日常", 0.20D),
        Map.entry("办公", 0.35D)
    );

    public record Evidence(
        double score,
        double titleScore,
        double headingScore,
        double summaryScore,
        boolean strongEvidence,
        List<String> titleMatches,
        List<String> headingMatches
    ) {
    }

    private ScopeLexicalScorer() {
    }

    public static Evidence score(String scopeName,
                                 Collection<String> aliases,
                                 String description,
                                 String title,
                                 Collection<String> headings,
                                 String summary) {
        List<WeightedTerm> terms = buildTerms(scopeName, aliases, description);
        ZoneEvidence titleEvidence = match(title, terms);
        ZoneEvidence headingEvidence = match(String.join(" ", safe(headings)), terms);
        ZoneEvidence summaryEvidence = match(summary, terms);
        double lexicalScore = clamp(
            0.50D * titleEvidence.score()
                + 0.30D * headingEvidence.score()
                + 0.20D * summaryEvidence.score()
        );
        boolean strongEvidence = titleEvidence.identityMatchWeight() >= TITLE_STRONG_EVIDENCE
            || headingEvidence.identityMatchWeight() >= HEADING_STRONG_EVIDENCE;
        return new Evidence(
            lexicalScore,
            titleEvidence.score(),
            headingEvidence.score(),
            summaryEvidence.score(),
            strongEvidence,
            titleEvidence.matches(),
            headingEvidence.matches()
        );
    }

    private static List<WeightedTerm> buildTerms(String scopeName,
                                                  Collection<String> aliases,
                                                  String description) {
        Map<String, WeightedTerm> terms = new LinkedHashMap<>();
        addIdentityTerm(terms, scopeName, 1.35D);
        if (aliases != null) {
            aliases.stream()
                .filter(item -> item != null && !item.isBlank())
                .forEach(alias -> addIdentityTerm(terms, alias, 1.20D));
        }
        splitSegments(description).forEach(segment -> addDescriptionTerm(terms, segment));
        return terms.values().stream()
            .sorted(Comparator.comparingInt((WeightedTerm item) -> item.text().length()).reversed())
            .limit(MAX_TERMS)
            .toList();
    }

    private static void addIdentityTerm(Map<String, WeightedTerm> terms,
                                        String rawValue,
                                        double weight) {
        String normalized = normalizeTerm(rawValue);
        if (normalized.length() < 2) {
            return;
        }
        terms.merge(normalized, new WeightedTerm(normalized, weight, true), ScopeLexicalScorer::higherWeight);
        if (!containsHan(normalized)) {
            return;
        }
        for (String fragment : fragments(normalized)) {
            double fragmentWeight = fragment.length() >= 3 ? 0.50D : 0.42D;
            double commonWeight = COMMON_TERM_WEIGHTS.getOrDefault(fragment, 1D);
            fragmentWeight *= commonWeight;
            terms.merge(fragment,
                new WeightedTerm(fragment, fragmentWeight, true),
                ScopeLexicalScorer::higherWeight);
        }
    }

    private static void addDescriptionTerm(Map<String, WeightedTerm> terms,
                                           String rawValue) {
        String normalized = normalizeTerm(rawValue);
        if (normalized.length() < 2) {
            return;
        }
        if (normalized.length() <= 8) {
            terms.merge(normalized,
                new WeightedTerm(normalized, 0.34D, false),
                ScopeLexicalScorer::higherWeight);
        }
        if (!containsHan(normalized)) {
            return;
        }
        for (String fragment : fragments(normalized)) {
            double commonWeight = COMMON_TERM_WEIGHTS.getOrDefault(fragment, 1D);
            double fragmentWeight = (fragment.length() >= 3 ? 0.28D : 0.22D) * commonWeight;
            terms.merge(fragment,
                new WeightedTerm(fragment, fragmentWeight, false),
                ScopeLexicalScorer::higherWeight);
        }
    }

    private static ZoneEvidence match(String text, List<WeightedTerm> terms) {
        String source = normalizeTerm(text);
        if (source.isBlank() || terms.isEmpty()) {
            return ZoneEvidence.empty();
        }
        double rawWeight = 0D;
        double identityMatchWeight = 0D;
        List<String> matches = new ArrayList<>();
        for (WeightedTerm term : terms) {
            int occurrences = countOccurrences(source, term.text());
            if (occurrences <= 0) {
                continue;
            }
            double termWeight = term.weight() * (1D + 0.25D * (Math.min(3, occurrences) - 1));
            rawWeight += termWeight;
            if (term.identityTerm()) {
                identityMatchWeight += termWeight;
            }
            if (matches.size() < 8) {
                matches.add(term.text());
            }
        }
        double score = 1D - Math.exp(-rawWeight / SATURATION);
        return new ZoneEvidence(clamp(score), identityMatchWeight, List.copyOf(matches));
    }

    private static List<String> fragments(String value) {
        Set<String> result = new LinkedHashSet<>();
        for (int index = 0; index + 2 <= value.length(); index++) {
            String bigram = value.substring(index, index + 2);
            if (bigram.codePoints().allMatch(Character::isLetterOrDigit)) {
                result.add(bigram);
            }
        }
        if (value.length() <= 8) {
            for (int index = 0; index + 3 <= value.length(); index++) {
                result.add(value.substring(index, index + 3));
            }
        }
        return List.copyOf(result);
    }

    private static List<String> splitSegments(String text) {
        if (text == null || text.isBlank()) {
            return List.of();
        }
        return Arrays.stream(text.split("[\\s，,。；;：:、/（）()【】\\[\\]{}\"“”'']+"))
            .map(String::trim)
            .filter(item -> !item.isBlank())
            .toList();
    }

    private static WeightedTerm higherWeight(WeightedTerm left, WeightedTerm right) {
        if (Double.compare(left.weight(), right.weight()) != 0) {
            return left.weight() >= right.weight() ? left : right;
        }
        return left.identityTerm() ? left : right;
    }

    private static int countOccurrences(String source, String term) {
        int count = 0;
        int index = 0;
        while ((index = source.indexOf(term, index)) >= 0) {
            count++;
            index += term.length();
        }
        return count;
    }

    private static String normalizeTerm(String value) {
        return value == null
            ? ""
            : value.replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\"'\\[\\]{}]+", "")
                .toLowerCase(Locale.ROOT);
    }

    private static boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private static Collection<String> safe(Collection<String> values) {
        return values == null ? List.of() : values;
    }

    private static double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private record WeightedTerm(String text, double weight, boolean identityTerm) {
    }

    private record ZoneEvidence(double score,
                                double identityMatchWeight,
                                List<String> matches) {

        private static ZoneEvidence empty() {
            return new ZoneEvidence(0D, 0D, List.of());
        }
    }
}
