package org.javaup.ai.manage.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeDecisionPolicyTest {

    private static final ScopeDecisionPolicy.Thresholds THRESHOLDS = new ScopeDecisionPolicy.Thresholds(
        0.55D,
        0.08D,
        0.42D,
        0.30D,
        0.10D,
        0.38D,
        0.10D
    );

    @Test
    void shouldAcceptDominantCandidateEvenWhenAbsoluteScoreIsModerate() {
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(List.of(
            candidate("hr_management", 0.4721D, false),
            candidate("tech_center", 0.2666D, false),
            candidate("finance_expense", 0.2414D, false)
        ), THRESHOLDS);

        assertTrue(decision.accepted());
        assertEquals(ScopeDecisionPolicy.Tier.CLEAR_WINNER, decision.tier());
        assertEquals("hr_management", decision.top().scopeCode());
    }

    @Test
    void shouldRejectLowSeparationCandidates() {
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(List.of(
            candidate("hr_management", 0.5200D, false),
            candidate("tech_center", 0.5000D, false)
        ), THRESHOLDS);

        assertFalse(decision.accepted());
        assertEquals(ScopeDecisionPolicy.Tier.NONE, decision.tier());
    }

    @Test
    void shouldAcceptHighConfidenceCandidate() {
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(List.of(
            candidate("hr_management", 0.6000D, false),
            candidate("tech_center", 0.5100D, false)
        ), THRESHOLDS);

        assertTrue(decision.accepted());
        assertEquals(ScopeDecisionPolicy.Tier.HIGH_CONFIDENCE, decision.tier());
    }

    @Test
    void shouldAcceptEvidenceDrivenCandidate() {
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(List.of(
            candidate("hr_management", 0.3900D, true),
            candidate("tech_center", 0.2800D, false)
        ), THRESHOLDS);

        assertTrue(decision.accepted());
        assertEquals(ScopeDecisionPolicy.Tier.EVIDENCE_DRIVEN, decision.tier());
    }

    @Test
    void shouldRejectEvidenceDrivenCandidateWithoutStrongEvidence() {
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(List.of(
            candidate("hr_management", 0.4000D, false),
            candidate("tech_center", 0.3900D, false)
        ), THRESHOLDS);

        assertFalse(decision.accepted());
        assertEquals(ScopeDecisionPolicy.Tier.NONE, decision.tier());
    }

    private static ScopeDecisionPolicy.Candidate candidate(String code,
                                                          double score,
                                                          boolean strongEvidence) {
        return new ScopeDecisionPolicy.Candidate(code, code, score, strongEvidence);
    }
}
