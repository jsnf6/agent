package org.javaup.ai.manage.support;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 根据总分、领先差距和强证据决定文档最终归属的知识域。
 */
public final class ScopeDecisionPolicy {

    public enum Tier {
        HIGH_CONFIDENCE,
        CLEAR_WINNER,
        EVIDENCE_DRIVEN,
        NONE
    }

    public record Candidate(
        String scopeCode,
        String scopeName,
        double score,
        boolean strongEvidence
    ) {
    }

    public record Thresholds(
        double highConfidenceScore,
        double highConfidenceGap,
        double clearMinScore,
        double clearRelativeGap,
        double clearGap,
        double evidenceMinScore,
        double evidenceGap
    ) {
    }

    public record Decision(
        Candidate top,
        double absoluteGap,
        double relativeGap,
        boolean accepted,
        Tier tier,
        String reason
    ) {
    }

    private ScopeDecisionPolicy() {
    }

    public static Decision decide(List<Candidate> candidates, Thresholds thresholds) {
        if (candidates == null || candidates.isEmpty()) {
            return new Decision(null, 0D, 0D, false, Tier.NONE, "没有可用的知识域候选");
        }
        List<Candidate> ranked = new ArrayList<>(candidates);
        ranked.sort(Comparator.comparingDouble(Candidate::score).reversed());

        Candidate top = ranked.get(0);
        double secondScore = ranked.size() > 1 ? ranked.get(1).score() : 0D;
        double absoluteGap = Math.max(0D, top.score() - secondScore);
        double relativeGap = top.score() <= 0D ? 0D : clamp(absoluteGap / top.score());

        Tier tier = resolveTier(top, absoluteGap, relativeGap, thresholds);
        boolean accepted = tier != Tier.NONE;
        String reason = accepted
            ? "知识域自动绑定（" + tierName(tier) + "），第一名=" + round(top.score())
                + "，绝对领先=" + round(absoluteGap) + "，领先比例=" + round(relativeGap)
            : "知识域置信度不足，第一名=" + round(top.score())
                + "，绝对领先=" + round(absoluteGap) + "，领先比例=" + round(relativeGap)
                + "，强证据=" + (top.strongEvidence() ? "是" : "否");
        return new Decision(top, absoluteGap, relativeGap, accepted, tier, reason);
    }

    private static Tier resolveTier(Candidate top,
                                    double absoluteGap,
                                    double relativeGap,
                                    Thresholds thresholds) {
        if (top.score() >= thresholds.highConfidenceScore()
            && absoluteGap >= thresholds.highConfidenceGap()) {
            return Tier.HIGH_CONFIDENCE;
        }
        if (top.score() >= thresholds.clearMinScore()
            && relativeGap >= thresholds.clearRelativeGap()
            && absoluteGap >= thresholds.clearGap()) {
            return Tier.CLEAR_WINNER;
        }
        if (top.score() >= thresholds.evidenceMinScore()
            && top.strongEvidence()
            && absoluteGap >= thresholds.evidenceGap()) {
            return Tier.EVIDENCE_DRIVEN;
        }
        return Tier.NONE;
    }

    private static String tierName(Tier tier) {
        return switch (tier) {
            case HIGH_CONFIDENCE -> "高置信";
            case CLEAR_WINNER -> "清晰胜出";
            case EVIDENCE_DRIVEN -> "强证据";
            case NONE -> "未命中";
        };
    }

    private static double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private static double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }
}
