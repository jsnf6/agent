package org.javaup.ai.manage.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class KnowledgeRouteScoringSupportTest {

    @Test
    void shouldNormalizeSemanticSimilarityIntoUnitRange() {
        assertEquals(0D, KnowledgeRouteScoringSupport.semanticUnit(0.20D, 0.25D, 0.80D));
        assertEquals(1D, KnowledgeRouteScoringSupport.semanticUnit(0.85D, 0.25D, 0.80D));
        assertEquals(0.50D, KnowledgeRouteScoringSupport.semanticUnit(0.50D, 0.20D, 0.80D), 0.0001D);
    }

    @Test
    void shouldPreferTopicAliasEvidenceOverGenericQuestionWords() {
        KnowledgeRouteScoringSupport.LexicalEvidence annualLeave = KnowledgeRouteScoringSupport.lexicalEvidence(
            "年假怎么申请",
            "年假申请与审批流程 员工提交休假申请后由直属主管审批",
            List.of("年假", "休假申请")
        );
        KnowledgeRouteScoringSupport.LexicalEvidence generic = KnowledgeRouteScoringSupport.lexicalEvidence(
            "年假怎么申请",
            "通用流程说明 请按照公司制度执行",
            List.of("流程", "制度")
        );

        assertTrue(annualLeave.aliasHit());
        assertTrue(annualLeave.score() > generic.score());
    }

    @Test
    void shouldIgnoreMissingSemanticWeightWhenCombiningScores() {
        double score = KnowledgeRouteScoringSupport.weightedScore(
            new double[]{0D, 0.30D},
            new double[]{0.90D, 0.60D}
        );

        assertEquals(0.60D, score, 0.0001D);
    }

    @Test
    void shouldComputeRelativeMargin() {
        assertEquals(0.50D, KnowledgeRouteScoringSupport.marginRatio(0.60D, 0.30D), 0.0001D);
        assertEquals(1D, KnowledgeRouteScoringSupport.marginRatio(0.60D, 0D), 0.0001D);
        assertEquals(0D, KnowledgeRouteScoringSupport.marginRatio(0D, 0D), 0.0001D);
    }

    @Test
    void shouldSaturateExternalLexicalScore() {
        double low = KnowledgeRouteScoringSupport.normalizedExternalLexical(2D, 20D);
        double high = KnowledgeRouteScoringSupport.normalizedExternalLexical(40D, 20D);

        assertFalse(low >= high);
        assertTrue(high < 1D);
    }
}
