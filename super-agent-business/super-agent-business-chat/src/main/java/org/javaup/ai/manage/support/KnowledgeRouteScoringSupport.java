package org.javaup.ai.manage.support;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 知识路由使用的纯计算能力，统一输出 0~1 分数。
 */
public final class KnowledgeRouteScoringSupport {

    private static final Pattern QUERY_SEGMENT_PATTERN = Pattern.compile("[\\p{IsHan}]+|[a-z0-9]+");
    private static final Set<String> QUERY_STOP_TERMS = Set.of(
        "怎么", "如何", "什么", "哪些", "是否", "可以", "需要", "请问", "一下", "相关",
        "关于", "进行", "应该", "这个", "那个", "我们", "你们", "他们", "有没有", "能否",
        "为什么", "多少", "哪里", "哪个", "谁", "吗", "呢", "的", "了", "和", "与", "或"
    );
    private static final double NGRAM_SATURATION = 1.8D;

    private KnowledgeRouteScoringSupport() {
    }

    public record LexicalEvidence(double score,
                                  List<String> matches,
                                  boolean aliasHit) {

        private static LexicalEvidence empty() {
            return new LexicalEvidence(0D, List.of(), false);
        }
    }

    public static double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || right.length == 0 || left.length != right.length) {
            return 0D;
        }
        double dot = 0D;
        double leftNorm = 0D;
        double rightNorm = 0D;
        for (int index = 0; index < left.length; index++) {
            dot += left[index] * right[index];
            leftNorm += left[index] * left[index];
            rightNorm += right[index] * right[index];
        }
        if (leftNorm <= 0D || rightNorm <= 0D) {
            return 0D;
        }
        return dot / (Math.sqrt(leftNorm) * Math.sqrt(rightNorm));
    }

    public static double semanticUnit(double similarity, double floor, double ceil) {
        if (!Double.isFinite(similarity)) {
            return 0D;
        }
        if (ceil <= floor) {
            return clamp(similarity);
        }
        return clamp((similarity - floor) / (ceil - floor));
    }

    public static LexicalEvidence lexicalEvidence(String query,
                                                   String content,
                                                   Collection<String> aliases) {
        String normalizedQuery = normalize(query);
        String normalizedContent = normalize(content);
        if (normalizedQuery.isBlank() || normalizedContent.isBlank()) {
            return LexicalEvidence.empty();
        }

        List<String> matches = new ArrayList<>();
        double aliasScore = 0D;
        if (aliases != null) {
            for (String alias : aliases) {
                String normalizedAlias = normalize(alias);
                if (normalizedAlias.length() < 2 || !normalizedQuery.contains(normalizedAlias)) {
                    continue;
                }
                aliasScore = Math.max(aliasScore,
                    Math.min(0.95D, 0.68D + 0.05D * Math.min(6, normalizedAlias.length())));
                if (matches.size() < 8) {
                    matches.add(alias.trim());
                }
            }
        }

        double phraseScore = normalizedQuery.length() >= 5 && normalizedContent.contains(normalizedQuery)
            ? 0.95D
            : 0D;
        double ngramScore = 0D;
        Map<String, Double> terms = extractQueryTerms(normalizedQuery);
        if (!terms.isEmpty()) {
            double matchedWeight = 0D;
            double totalWeight = 0D;
            for (Map.Entry<String, Double> entry : terms.entrySet()) {
                totalWeight += entry.getValue();
                if (normalizedContent.contains(entry.getKey())) {
                    matchedWeight += entry.getValue();
                    if (matches.size() < 8) {
                        matches.add(entry.getKey());
                    }
                }
            }
            if (totalWeight > 0D) {
                ngramScore = 1D - Math.exp(-NGRAM_SATURATION * (matchedWeight / totalWeight));
            }
        }
        return new LexicalEvidence(
            clamp(Math.max(aliasScore, Math.max(phraseScore, ngramScore))),
            List.copyOf(matches),
            aliasScore > 0D
        );
    }

    public static double weightedScore(double[] weights, double[] values) {
        if (weights == null || values == null || weights.length != values.length) {
            return 0D;
        }
        double weighted = 0D;
        double totalWeight = 0D;
        for (int index = 0; index < weights.length; index++) {
            double weight = weights[index];
            if (!Double.isFinite(weight) || weight <= 0D) {
                continue;
            }
            totalWeight += weight;
            weighted += weight * clamp(values[index]);
        }
        return totalWeight <= 0D ? 0D : clamp(weighted / totalWeight);
    }

    public static double marginRatio(double top, double second) {
        if (top <= 0D) {
            return 0D;
        }
        if (second <= 0D) {
            return 1D;
        }
        return clamp((top - second) / top);
    }

    public static double normalizedExternalLexical(double rawScore, double saturation) {
        if (!Double.isFinite(rawScore) || rawScore <= 0D) {
            return 0D;
        }
        if (saturation <= 0D) {
            return clamp(rawScore);
        }
        return clamp(1D - Math.exp(-rawScore / saturation));
    }

    public static String normalize(String value) {
        return value == null
            ? ""
            : value.replaceAll("[\\s>`*#_\\-，,。；;：:（）()【】“”\"'\\[\\]{}]+", "")
                .toLowerCase(Locale.ROOT);
    }

    public static double clamp(double value) {
        if (!Double.isFinite(value)) {
            return 0D;
        }
        return Math.max(0D, Math.min(1D, value));
    }

    private static Map<String, Double> extractQueryTerms(String normalizedQuery) {
        Map<String, Double> terms = new LinkedHashMap<>();
        Matcher matcher = QUERY_SEGMENT_PATTERN.matcher(normalizedQuery);
        while (matcher.find()) {
            String segment = matcher.group();
            if (segment.codePoints().allMatch(Character::isLetterOrDigit)
                && !segment.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN)) {
                if (segment.length() >= 2 && !QUERY_STOP_TERMS.contains(segment)) {
                    terms.put(segment, Math.min(1.5D, 0.6D + 0.2D * segment.length()));
                }
                continue;
            }
            if (segment.length() < 2) {
                continue;
            }
            for (int index = 0; index + 2 <= segment.length(); index++) {
                String bigram = segment.substring(index, index + 2);
                if (!QUERY_STOP_TERMS.contains(bigram)) {
                    terms.putIfAbsent(bigram, 1D);
                }
            }
        }
        return terms;
    }
}
