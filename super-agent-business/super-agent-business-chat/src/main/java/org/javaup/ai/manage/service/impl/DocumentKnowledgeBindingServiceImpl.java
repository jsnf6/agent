package org.javaup.ai.manage.service.impl;

import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.service.DocumentKnowledgeBindingService;
import org.javaup.ai.manage.service.KnowledgeRouteIndexService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentKnowledgeBindingResult;
import org.javaup.ai.manage.support.ScopeDecisionPolicy;
import org.javaup.ai.manage.support.ScopeLexicalScorer;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 基于人工维护的 scope/topic 为文档做细粒度分类。
 */
@Slf4j
@AllArgsConstructor
@Service
public class DocumentKnowledgeBindingServiceImpl implements DocumentKnowledgeBindingService {

    private static final String SOURCE_AUTO = "auto";
    private static final String SOURCE_MANUAL = "manual";
    private static final int EMBEDDING_BATCH_SIZE = 10;
    private static final int TAG_LIMIT = 16;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper relationMapper;
    private final DocumentManageProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> routeIndexServiceProvider;
    private final ObjectMapper objectMapper;
    private final UidGenerator uidGenerator;

    @Override
    public DocumentKnowledgeBindingResult bind(Long documentId,
                                               DocumentAnalysisResult analysisResult,
                                               List<SuperAgentDocumentStructureNode> structureNodes) {
        DocumentManageProperties.KnowledgeBinding config = properties.getKnowledgeBinding();
        if (documentId == null || Boolean.FALSE.equals(config.getEnabled())) {
            return DocumentKnowledgeBindingResult.skipped(documentId, "自动绑定未启用");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            return DocumentKnowledgeBindingResult.skipped(documentId, "文档不存在或已删除");
        }
        try {
            return doBind(document, analysisResult, structureNodes, config);
        }
        catch (Exception exception) {
            log.warn("文档自动绑定 scope/topic 失败，保留原有分类，documentId={}", documentId, exception);
            return DocumentKnowledgeBindingResult.skipped(documentId, "自动绑定失败: " + exception.getMessage());
        }
    }

    private DocumentKnowledgeBindingResult doBind(SuperAgentDocument document,
                                                  DocumentAnalysisResult analysisResult,
                                                  List<SuperAgentDocumentStructureNode> structureNodes,
                                                  DocumentManageProperties.KnowledgeBinding config) {
        Long documentId = document.getId();
        List<SuperAgentKnowledgeScopeNode> scopes = scopeNodeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
                .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentKnowledgeScopeNode::getSortOrder, SuperAgentKnowledgeScopeNode::getId));
        if (scopes.isEmpty()) {
            return DocumentKnowledgeBindingResult.skipped(documentId, "系统中没有启用的知识域");
        }
        List<SuperAgentKnowledgeTopicNode> topics = topicNodeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
                .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentKnowledgeTopicNode::getSortOrder, SuperAgentKnowledgeTopicNode::getId));
        Map<String, SuperAgentKnowledgeScopeNode> scopeMap = scopes.stream()
            .collect(Collectors.toMap(SuperAgentKnowledgeScopeNode::getScopeCode, item -> item, (left, right) -> left));
        Map<String, SuperAgentKnowledgeTopicNode> topicMap = topics.stream()
            .collect(Collectors.toMap(SuperAgentKnowledgeTopicNode::getTopicCode, item -> item, (left, right) -> left));
        SuperAgentDocumentProfile profile = loadProfile(documentId);
        List<SuperAgentTopicDocumentRelation> relations = relationMapper.selectList(
            new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
                .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId));

        ManualScopeResolution manualResolution = resolveManualScope(document, scopeMap, topicMap, relations);
        if (manualResolution.conflict()) {
            return DocumentKnowledgeBindingResult.skipped(documentId, manualResolution.reason());
        }

        DocumentViews views = buildDocumentViews(document, profile, analysisResult, structureNodes, config);
        List<ScopeScore> scopeScores = rankScopes(views, scopes, scopeMap, config);
        ScopeScore chosenScope;
        double scopeConfidence;
        boolean scopeAutoSelected;
        String scopeReason;
        if (manualResolution.scope() != null) {
            chosenScope = scopeScores.stream()
                .filter(item -> Objects.equals(item.scope().getScopeCode(), manualResolution.scope().getScopeCode()))
                .findFirst()
                .orElse(new ScopeScore(manualResolution.scope(), 0D, 0D, 0D, 0D, 0D, false));
            scopeConfidence = 1D;
            scopeAutoSelected = false;
            scopeReason = "文档已人工指定知识域，自动流程仅在该知识域下选择主题";
        }
        else {
            ScopeDecision decision = decideScope(scopeScores, config);
            if (!decision.accepted()) {
                log.info("文档未达到知识域自动绑定阈值: documentId={}, topScores={}, reason={}",
                    documentId, summarizeScopeScores(scopeScores), decision.reason());
                return DocumentKnowledgeBindingResult.skipped(documentId, decision.reason());
            }
            chosenScope = decision.top();
            scopeConfidence = decision.relativeGap();
            scopeAutoSelected = true;
            scopeReason = decision.reason();
        }

        List<SuperAgentKnowledgeTopicNode> scopeTopics = topics.stream()
            .filter(topic -> Objects.equals(topic.getScopeCode(), chosenScope.scope().getScopeCode()))
            .toList();
        List<SectionEvidence> evidences = buildSectionEvidence(document, profile, analysisResult, structureNodes, config);
        List<TopicScore> topicScores = rankTopics(scopeTopics, chosenScope.scope(), evidences, config);
        List<DocumentKnowledgeBindingResult.TopicBinding> selectedTopics = topicScores.stream()
            .filter(item -> item.score() >= config.getTopicMinScore())
            .limit(Math.max(1, config.getTopicMaxCount()))
            .map(item -> new DocumentKnowledgeBindingResult.TopicBinding(
                item.topic().getTopicCode(), item.topic().getTopicName(), item.score(), item.reason()))
            .toList();

        List<String> selectedTopicNames = selectedTopics.stream()
            .map(DocumentKnowledgeBindingResult.TopicBinding::topicName)
            .toList();
        boolean documentChanged = applyDocumentBinding(document, chosenScope, scopeAutoSelected, selectedTopicNames);
        persistTopicRelations(documentId, relations, selectedTopics);
        if (documentChanged) {
            documentMapper.updateById(document);
        }
        refreshRouteIndex(documentId);

        String reason = scopeReason
            + "；知识域候选=" + summarizeScopeScores(scopeScores)
            + "；主题候选=" + summarizeTopicScores(topicScores);
        log.info("文档 scope/topic 自动绑定完成: documentId={}, scopeCode={}, scopeName={}, scopeScore={}, scopeConfidence={}, scopeAutoSelected={}, topics={}, reason={}",
            documentId,
            chosenScope.scope().getScopeCode(),
            chosenScope.scope().getScopeName(),
            round(chosenScope.score()),
            round(scopeConfidence),
            scopeAutoSelected,
            selectedTopics,
            reason);
        return new DocumentKnowledgeBindingResult(
            documentId,
            chosenScope.scope().getScopeCode(),
            chosenScope.scope().getScopeName(),
            chosenScope.score(),
            scopeConfidence,
            scopeAutoSelected,
            selectedTopics,
            reason
        );
    }

    private List<ScopeScore> rankScopes(DocumentViews views,
                                        List<SuperAgentKnowledgeScopeNode> scopes,
                                        Map<String, SuperAgentKnowledgeScopeNode> scopeMap,
                                        DocumentManageProperties.KnowledgeBinding config) {
        List<ScopeViews> scopeViews = scopes.stream()
            .map(scope -> buildScopeViews(scope, scopeMap))
            .toList();
        List<float[]> identityEmbeddings = embedTexts(scopeViews.stream().map(ScopeViews::identityText).toList());
        List<float[]> semanticEmbeddings = embedTexts(scopeViews.stream().map(ScopeViews::semanticText).toList());
        float[] titleEmbedding = embedTexts(List.of(views.titleView())).stream().findFirst().orElse(null);
        float[] summaryEmbedding = StrUtil.isBlank(views.summaryView())
            ? null
            : embedTexts(List.of(views.summaryView())).stream().findFirst().orElse(null);
        List<float[]> headingEmbeddings = embedTexts(views.headingTexts());
        boolean semanticAvailable = identityEmbeddings.size() == scopes.size()
            && semanticEmbeddings.size() == scopes.size();

        List<ScopeScore> scores = new ArrayList<>();
        for (int index = 0; index < scopes.size(); index++) {
            SuperAgentKnowledgeScopeNode scope = scopes.get(index);
            float[] identityEmbedding = semanticAvailable ? identityEmbeddings.get(index) : null;
            float[] semanticEmbedding = semanticAvailable ? semanticEmbeddings.get(index) : null;
            Double titleSemantic = null;
            Double headingSemantic = null;
            Double summarySemantic = null;
            if (semanticAvailable) {
                titleSemantic = semanticUnit(cosineSimilarity(titleEmbedding, identityEmbedding), config);
                if (!headingEmbeddings.isEmpty() && semanticEmbedding != null) {
                    List<Double> similarities = new ArrayList<>();
                    for (float[] headingEmbedding : headingEmbeddings) {
                        similarities.add(cosineSimilarity(headingEmbedding, semanticEmbedding));
                    }
                    headingSemantic = semanticUnit(averageTop(similarities, 3), config);
                }
                if (summaryEmbedding != null) {
                    summarySemantic = semanticUnit(cosineSimilarity(summaryEmbedding, semanticEmbedding), config);
                }
            }
            ScopeLexicalScorer.Evidence lexicalEvidence = ScopeLexicalScorer.score(
                scope.getScopeName(),
                parseDelimited(scope.getAliases()),
                scope.getDescription(),
                views.titleView(),
                views.headingTexts(),
                join(views.summaryView(), views.keywordText())
            );
            double finalScore = weightedScopeScore(
                titleSemantic,
                headingSemantic,
                summarySemantic,
                lexicalEvidence.score(),
                config
            );
            log.debug("scope 分项得分: scopeCode={}, score={}, titleSemantic={}, headingSemantic={}, summarySemantic={}, lexical={}, strongEvidence={}, titleMatches={}, headingMatches={}",
                scope.getScopeCode(),
                round(finalScore),
                titleSemantic == null ? "-" : round(titleSemantic),
                headingSemantic == null ? "-" : round(headingSemantic),
                summarySemantic == null ? "-" : round(summarySemantic),
                round(lexicalEvidence.score()),
                lexicalEvidence.strongEvidence(),
                lexicalEvidence.titleMatches(),
                lexicalEvidence.headingMatches());
            scores.add(new ScopeScore(
                scope,
                clamp(finalScore),
                titleSemantic == null ? 0D : titleSemantic,
                headingSemantic == null ? 0D : headingSemantic,
                summarySemantic == null ? 0D : summarySemantic,
                lexicalEvidence.score(),
                lexicalEvidence.strongEvidence()
            ));
        }
        scores.sort(Comparator.comparingDouble(ScopeScore::score).reversed());
        return scores;
    }

    private ScopeDecision decideScope(List<ScopeScore> scopeScores,
                                      DocumentManageProperties.KnowledgeBinding config) {
        if (scopeScores.isEmpty()) {
            return new ScopeDecision(null, 0D, false, "没有可用的知识域候选");
        }
        List<ScopeDecisionPolicy.Candidate> candidates = scopeScores.stream()
            .map(item -> new ScopeDecisionPolicy.Candidate(
                item.scope().getScopeCode(),
                item.scope().getScopeName(),
                item.score(),
                item.strongEvidence()))
            .toList();
        ScopeDecisionPolicy.Thresholds thresholds = new ScopeDecisionPolicy.Thresholds(
            config.getScopeHighConfidenceScore(),
            config.getScopeHighConfidenceGap(),
            config.getScopeClearMinScore(),
            config.getScopeClearRelativeGap(),
            config.getScopeClearGap(),
            config.getScopeEvidenceMinScore(),
            config.getScopeEvidenceGap()
        );
        ScopeDecisionPolicy.Decision decision = ScopeDecisionPolicy.decide(candidates, thresholds);
        ScopeScore top = decision.top() == null
            ? scopeScores.get(0)
            : scopeScores.stream()
                .filter(item -> Objects.equals(item.scope().getScopeCode(), decision.top().scopeCode()))
                .findFirst()
                .orElse(scopeScores.get(0));
        return new ScopeDecision(top, decision.relativeGap(), decision.accepted(), decision.reason());
    }

    private List<TopicScore> rankTopics(List<SuperAgentKnowledgeTopicNode> topics,
                                        SuperAgentKnowledgeScopeNode scope,
                                        List<SectionEvidence> evidences,
                                        DocumentManageProperties.KnowledgeBinding config) {
        if (topics.isEmpty() || evidences.isEmpty()) {
            return List.of();
        }
        List<String> topicTexts = topics.stream()
            .map(topic -> buildTopicText(scope, topic))
            .toList();
        List<String> evidenceTexts = evidences.stream().map(SectionEvidence::text).toList();
        List<float[]> topicEmbeddings = embedTexts(topicTexts);
        List<float[]> evidenceEmbeddings = embedTexts(evidenceTexts);
        boolean semanticAvailable = !topicEmbeddings.isEmpty() && topicEmbeddings.size() == topics.size();

        List<TopicScore> scores = new ArrayList<>();
        for (int topicIndex = 0; topicIndex < topics.size(); topicIndex++) {
            SuperAgentKnowledgeTopicNode topic = topics.get(topicIndex);
            String topicText = topicTexts.get(topicIndex);
            float[] topicEmbedding = semanticAvailable ? topicEmbeddings.get(topicIndex) : null;
            List<ItemScore> itemScores = new ArrayList<>();
            for (int evidenceIndex = 0; evidenceIndex < evidences.size(); evidenceIndex++) {
                SectionEvidence evidence = evidences.get(evidenceIndex);
                float[] evidenceEmbedding = evidenceIndex < evidenceEmbeddings.size()
                    ? evidenceEmbeddings.get(evidenceIndex)
                    : null;
                double semantic = cosineSimilarity(evidenceEmbedding, topicEmbedding);
                double semanticUnit = semanticUnit(semantic, config);
                double lexical = lexicalScore(evidence.text(), topicText);
                boolean aliasHit = containsAnyAlias(evidence.text(), topic.getTopicName(), topic.getAliases());
                double score = semanticAvailable
                    ? 0.65D * semanticUnit + 0.25D * lexical + (aliasHit ? 0.10D : 0D)
                    : 0.75D * lexical + (aliasHit ? 0.25D : 0D);
                itemScores.add(new ItemScore(clamp(score), semantic, lexical, aliasHit, evidence));
            }
            itemScores.sort(Comparator.comparingDouble(ItemScore::score).reversed());
            double maxScore = itemScores.isEmpty() ? 0D : itemScores.get(0).score();
            double topAverage = itemScores.stream().limit(3).mapToDouble(ItemScore::score).average().orElse(0D);
            double topicScore = clamp(0.65D * maxScore + 0.35D * topAverage);
            ItemScore best = itemScores.isEmpty() ? null : itemScores.get(0);
            String reason = best == null
                ? "没有可用于主题匹配的章节证据"
                : "最佳章节=" + best.evidence().heading()
                    + "；分数=" + round(topicScore)
                    + "；语义=" + round(best.semantic())
                    + "；关键词=" + round(best.lexical())
                    + (best.aliasHit() ? "；命中主题别名" : "");
            scores.add(new TopicScore(topic, topicScore, reason));
        }
        scores.sort(Comparator.comparingDouble(TopicScore::score).reversed());
        return scores;
    }

    private DocumentViews buildDocumentViews(SuperAgentDocument document,
                                             SuperAgentDocumentProfile profile,
                                             DocumentAnalysisResult analysisResult,
                                             List<SuperAgentDocumentStructureNode> structureNodes,
                                             DocumentManageProperties.KnowledgeBinding config) {
        String titleView = truncate(joinDistinct(
            document.getDocumentName(),
            document.getOriginalFileName()
        ), 900);
        List<String> headings = (structureNodes == null ? List.<SuperAgentDocumentStructureNode>of() : structureNodes).stream()
            .filter(Objects::nonNull)
            .filter(node -> Objects.equals(DocumentStructureNodeTypeEnum.SECTION.getCode(), node.getNodeType()))
            .map(node -> firstNonBlank(node.getSectionPath(), node.getCanonicalPath(), node.getTitle()))
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(Math.max(1, config.getHeadingViewLimit()))
            .toList();
        List<String> summaryParts = new ArrayList<>();
        List<String> keywordParts = new ArrayList<>();
        if (profile != null) {
            summaryParts.add(profile.getDocumentSummary());
            keywordParts.addAll(parseList(profile.getCoreTopics()));
        }
        if (summaryParts.stream().allMatch(StrUtil::isBlank) && analysisResult != null) {
            summaryParts.add(truncate(analysisResult.getParsedText(), 600));
        }
        String summaryView = truncate(join(summaryParts.toArray(String[]::new)), 1600);
        String keywordView = truncate(join(keywordParts.toArray(String[]::new)), 800);
        return new DocumentViews(titleView, headings, summaryView, keywordView);
    }

    private List<SectionEvidence> buildSectionEvidence(SuperAgentDocument document,
                                                       SuperAgentDocumentProfile profile,
                                                       DocumentAnalysisResult analysisResult,
                                                       List<SuperAgentDocumentStructureNode> structureNodes,
                                                       DocumentManageProperties.KnowledgeBinding config) {
        List<SuperAgentDocumentStructureNode> nodes = (structureNodes == null
            ? List.<SuperAgentDocumentStructureNode>of()
            : structureNodes).stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.comparing(SuperAgentDocumentStructureNode::getNodeNo,
                Comparator.nullsLast(Integer::compareTo)))
            .toList();
        Map<Long, SuperAgentDocumentStructureNode> nodeMap = nodes.stream()
            .filter(node -> node.getId() != null)
            .collect(Collectors.toMap(SuperAgentDocumentStructureNode::getId, item -> item, (left, right) -> left));
        Set<Long> sectionIds = nodes.stream()
            .filter(node -> Objects.equals(DocumentStructureNodeTypeEnum.SECTION.getCode(), node.getNodeType()))
            .map(SuperAgentDocumentStructureNode::getId)
            .filter(Objects::nonNull)
            .collect(Collectors.toSet());
        Map<Long, List<SuperAgentDocumentStructureNode>> itemsBySection = new LinkedHashMap<>();
        for (SuperAgentDocumentStructureNode node : nodes) {
            if (!isListLike(node)) {
                continue;
            }
            Long sectionId = findAncestorSectionId(node, nodeMap, sectionIds);
            if (sectionId != null) {
                itemsBySection.computeIfAbsent(sectionId, ignored -> new ArrayList<>()).add(node);
            }
        }

        List<SectionEvidence> evidences = new ArrayList<>();
        List<SuperAgentDocumentStructureNode> sections = nodes.stream()
            .filter(node -> Objects.equals(DocumentStructureNodeTypeEnum.SECTION.getCode(), node.getNodeType()))
            .limit(Math.max(1, config.getSectionEvidenceLimit()))
            .toList();
        for (SuperAgentDocumentStructureNode section : sections) {
            String heading = firstNonBlank(section.getSectionPath(), section.getCanonicalPath(), section.getTitle());
            String lead = leadingSentences(section.getContentText(), config.getSectionLeadChars());
            List<String> items = itemsBySection.getOrDefault(section.getId(), List.of()).stream()
                .limit(Math.max(1, config.getSectionItemLimit()))
                .map(item -> truncate(join(item.getTitle(), item.getContentText()), 160))
                .filter(StrUtil::isNotBlank)
                .toList();
            String text = truncate(join(heading, lead, String.join(" ", items)), 900);
            if (StrUtil.isNotBlank(text)) {
                evidences.add(new SectionEvidence(heading, text));
            }
        }
        if (evidences.isEmpty()) {
            String fallback = join(
                document.getDocumentName(),
                profile == null ? "" : profile.getDocumentSummary(),
                profile == null ? "" : profile.getCoreTopics(),
                analysisResult == null ? "" : truncate(analysisResult.getParsedText(), 400)
            );
            if (StrUtil.isNotBlank(fallback)) {
                evidences.add(new SectionEvidence(firstNonBlank(document.getDocumentName(), "文档概览"), truncate(fallback, 900)));
            }
        }
        return evidences;
    }

    private boolean applyDocumentBinding(SuperAgentDocument document,
                                         ScopeScore chosenScope,
                                         boolean scopeAutoSelected,
                                         List<String> selectedTopicNames) {
        boolean changed = false;
        boolean canUpdateScope = scopeAutoSelected || StrUtil.isBlank(document.getKnowledgeScopeCode());
        if (canUpdateScope
            && (!Objects.equals(document.getKnowledgeScopeCode(), chosenScope.scope().getScopeCode())
            || !Objects.equals(document.getKnowledgeScopeName(), chosenScope.scope().getScopeName()))) {
            document.setKnowledgeScopeCode(chosenScope.scope().getScopeCode());
            document.setKnowledgeScopeName(chosenScope.scope().getScopeName());
            changed = true;
        }
        String mergedTags = mergeTags(document.getDocumentTags(), chosenScope.scope().getScopeName(), selectedTopicNames);
        if (!Objects.equals(document.getDocumentTags(), mergedTags)) {
            document.setDocumentTags(mergedTags);
            changed = true;
        }
        return changed;
    }

    private void persistTopicRelations(Long documentId,
                                       List<SuperAgentTopicDocumentRelation> existingRelations,
                                       List<DocumentKnowledgeBindingResult.TopicBinding> selectedTopics) {
        Set<String> selectedCodes = selectedTopics.stream()
            .map(DocumentKnowledgeBindingResult.TopicBinding::topicCode)
            .collect(Collectors.toSet());
        Map<String, SuperAgentTopicDocumentRelation> existingByTopic = existingRelations.stream()
            .collect(Collectors.toMap(SuperAgentTopicDocumentRelation::getTopicCode, item -> item, (left, right) -> left));
        for (SuperAgentTopicDocumentRelation relation : existingRelations) {
            if (isManual(relation) || selectedCodes.contains(relation.getTopicCode())) {
                continue;
            }
            if (Objects.equals(relation.getStatus(), BusinessStatus.YES.getCode())) {
                relation.setStatus(BusinessStatus.NO.getCode());
                relationMapper.updateById(relation);
            }
        }
        for (DocumentKnowledgeBindingResult.TopicBinding selected : selectedTopics) {
            SuperAgentTopicDocumentRelation relation = existingByTopic.get(selected.topicCode());
            if (relation != null && isManual(relation)) {
                continue;
            }
            if (relation == null) {
                relation = new SuperAgentTopicDocumentRelation();
                relation.setId(uidGenerator.getUid());
                relation.setTopicCode(selected.topicCode());
                relation.setDocumentId(documentId);
            }
            relation.setRelationScore(BigDecimal.valueOf(selected.score()).setScale(4, RoundingMode.HALF_UP));
            relation.setRelationSource(SOURCE_AUTO);
            relation.setReason(truncate(selected.reason(), 1000));
            relation.setStatus(BusinessStatus.YES.getCode());
            if (relation.getCreateTime() == null) {
                relationMapper.insert(relation);
            }
            else {
                relationMapper.updateById(relation);
            }
        }
    }

    private ManualScopeResolution resolveManualScope(SuperAgentDocument document,
                                                     Map<String, SuperAgentKnowledgeScopeNode> scopeMap,
                                                     Map<String, SuperAgentKnowledgeTopicNode> topicMap,
                                                     List<SuperAgentTopicDocumentRelation> relations) {
        String scopeCode = StrUtil.blankToDefault(document.getKnowledgeScopeCode(), "").trim();
        if (StrUtil.isNotBlank(scopeCode)) {
            SuperAgentKnowledgeScopeNode scope = scopeMap.get(scopeCode);
            if (scope == null) {
                return new ManualScopeResolution(null, false,
                    "文档原知识域已失效，将按当前启用分类重新匹配: " + scopeCode);
            }
            return new ManualScopeResolution(scope, false, "使用文档已指定的知识域");
        }
        String scopeName = StrUtil.blankToDefault(document.getKnowledgeScopeName(), "").trim();
        if (StrUtil.isNotBlank(scopeName)) {
            SuperAgentKnowledgeScopeNode scope = scopeMap.values().stream()
                .filter(item -> Objects.equals(item.getScopeName(), scopeName))
                .findFirst()
                .orElse(null);
            if (scope != null) {
                return new ManualScopeResolution(scope, false, "根据文档已指定的知识域名匹配到知识域");
            }
        }
        Set<String> manualScopeCodes = new LinkedHashSet<>();
        for (SuperAgentTopicDocumentRelation relation : relations) {
            if (!isManual(relation) || !Objects.equals(relation.getStatus(), BusinessStatus.YES.getCode())) {
                continue;
            }
            SuperAgentKnowledgeTopicNode topic = topicMap.get(relation.getTopicCode());
            if (topic != null && scopeMap.containsKey(topic.getScopeCode())) {
                manualScopeCodes.add(topic.getScopeCode());
            }
        }
        if (manualScopeCodes.size() == 1) {
            return new ManualScopeResolution(scopeMap.get(manualScopeCodes.iterator().next()), false,
                "根据人工主题关联锁定知识域");
        }
        if (manualScopeCodes.size() > 1) {
            return new ManualScopeResolution(null, true, "文档存在跨知识域的人工主题关联，跳过自动绑定");
        }
        return new ManualScopeResolution(null, false, "文档未人工指定知识域");
    }

    private SuperAgentDocumentProfile loadProfile(Long documentId) {
        return documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
    }

    private ScopeViews buildScopeViews(SuperAgentKnowledgeScopeNode scope,
                                       Map<String, SuperAgentKnowledgeScopeNode> scopeMap) {
        List<String> path = new ArrayList<>();
        SuperAgentKnowledgeScopeNode current = scope;
        Set<String> visited = new LinkedHashSet<>();
        while (current != null && visited.add(current.getScopeCode())) {
            path.add(0, current.getScopeName());
            current = StrUtil.isBlank(current.getParentScopeCode())
                ? null
                : scopeMap.get(current.getParentScopeCode());
        }
        String identityText = truncate(join(
            String.join(" > ", path),
            scope.getAliases()
        ), 900);
        String semanticText = truncate(join(
            scope.getDescription(),
            scope.getExamples()
        ), 1400);
        if (StrUtil.isBlank(semanticText)) {
            semanticText = identityText;
        }
        return new ScopeViews(identityText, semanticText);
    }

    private String buildTopicText(SuperAgentKnowledgeScopeNode scope, SuperAgentKnowledgeTopicNode topic) {
        return truncate(join(
            scope.getScopeName() + " > " + topic.getTopicName(),
            topic.getTopicCode(),
            topic.getAliases(),
            topic.getDescription(),
            topic.getExamples(),
            topic.getAnswerShape(),
            topic.getExecutionPreference()
        ), 1400);
    }

    private List<float[]> embedTexts(Collection<String> texts) {
        if (texts == null || texts.isEmpty()) {
            return List.of();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return List.of();
        }
        List<String> normalized = texts.stream()
            .map(text -> StrUtil.blankToDefault(text, " ").trim())
            .toList();
        try {
            List<float[]> embeddings = new ArrayList<>(normalized.size());
            for (int startIndex = 0; startIndex < normalized.size(); startIndex += EMBEDDING_BATCH_SIZE) {
                int endIndex = Math.min(startIndex + EMBEDDING_BATCH_SIZE, normalized.size());
                List<float[]> currentBatch = embeddingModel.embed(normalized.subList(startIndex, endIndex));
                if (currentBatch == null || currentBatch.size() != endIndex - startIndex) {
                    return List.of();
                }
                embeddings.addAll(currentBatch);
            }
            return embeddings;
        }
        catch (Exception exception) {
            log.warn("文档分类向量计算失败，将退化到关键词匹配: textCount={}", normalized.size(), exception);
            return List.of();
        }
    }

    private double cosineSimilarity(float[] left, float[] right) {
        if (left == null || right == null || left.length == 0 || left.length != right.length) {
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

    private double weightedScopeScore(Double titleSemantic,
                                      Double headingSemantic,
                                      Double summarySemantic,
                                      double lexicalEvidence,
                                      DocumentManageProperties.KnowledgeBinding config) {
        double lexicalWeight = Math.max(0D, config.getScopeWeightLexical());
        double totalWeight = lexicalWeight;
        double weighted = lexicalWeight * clamp(lexicalEvidence);
        if (titleSemantic != null) {
            double weight = Math.max(0D, config.getScopeWeightTitle());
            totalWeight += weight;
            weighted += weight * clamp(titleSemantic);
        }
        if (headingSemantic != null) {
            double weight = Math.max(0D, config.getScopeWeightHeading());
            totalWeight += weight;
            weighted += weight * clamp(headingSemantic);
        }
        if (summarySemantic != null) {
            double weight = Math.max(0D, config.getScopeWeightSummary());
            totalWeight += weight;
            weighted += weight * clamp(summarySemantic);
        }
        return totalWeight <= 0D ? 0D : clamp(weighted / totalWeight);
    }

    private double averageTop(List<Double> values, int limit) {
        if (values == null || values.isEmpty()) {
            return 0D;
        }
        return values.stream()
            .filter(Objects::nonNull)
            .sorted(Comparator.reverseOrder())
            .limit(Math.max(1, limit))
            .mapToDouble(Double::doubleValue)
            .average()
            .orElse(0D);
    }

    private double semanticUnit(double similarity,
                                DocumentManageProperties.KnowledgeBinding config) {
        double floor = config.getCosineFloor();
        double ceil = config.getCosineCeil();
        if (ceil <= floor) {
            return clamp(similarity);
        }
        return clamp((similarity - floor) / (ceil - floor));
    }

    private double lexicalScore(String sourceText, String candidateText) {
        String source = normalize(sourceText);
        Set<String> terms = extractTerms(candidateText);
        if (source.isBlank() || terms.isEmpty()) {
            return 0D;
        }
        int matched = 0;
        for (String term : terms) {
            if (source.contains(term)) {
                matched++;
            }
        }
        return matched == 0 ? 0D : clamp((double) matched / Math.max(6D, Math.min(80D, terms.size())));
    }

    private Set<String> extractTerms(String text) {
        String normalized = StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT);
        Set<String> terms = new LinkedHashSet<>();
        for (String segment : normalized.split("[^\\p{IsHan}a-z0-9]+")) {
            String value = segment.trim();
            if (value.length() < 2) {
                continue;
            }
            if (!containsHan(value) || value.matches(".*[a-z0-9].*")) {
                terms.add(value);
                continue;
            }
            if (value.length() <= 8) {
                terms.add(value);
            }
            for (int index = 0; index + 2 <= value.length(); index++) {
                terms.add(value.substring(index, index + 2));
            }
        }
        return terms.stream().limit(160).collect(Collectors.toCollection(LinkedHashSet::new));
    }

    private boolean containsAnyAlias(String sourceText, String name, String aliases) {
        String source = normalize(sourceText);
        if (source.isBlank()) {
            return false;
        }
        List<String> candidates = new ArrayList<>();
        candidates.add(name);
        candidates.addAll(parseDelimited(aliases));
        return candidates.stream()
            .map(this::normalize)
            .filter(item -> item.length() >= 2)
            .anyMatch(source::contains);
    }

    private Long findAncestorSectionId(SuperAgentDocumentStructureNode node,
                                       Map<Long, SuperAgentDocumentStructureNode> nodeMap,
                                       Set<Long> sectionIds) {
        Long parentId = node.getParentNodeId();
        int depth = 0;
        while (parentId != null && depth++ < 32) {
            if (sectionIds.contains(parentId)) {
                return parentId;
            }
            SuperAgentDocumentStructureNode parent = nodeMap.get(parentId);
            if (parent == null) {
                return null;
            }
            parentId = parent.getParentNodeId();
        }
        return null;
    }

    private boolean isListLike(SuperAgentDocumentStructureNode node) {
        return Objects.equals(DocumentStructureNodeTypeEnum.STEP.getCode(), node.getNodeType())
            || Objects.equals(DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(), node.getNodeType());
    }

    private boolean isManual(SuperAgentTopicDocumentRelation relation) {
        return StrUtil.containsIgnoreCase(StrUtil.blankToDefault(relation.getRelationSource(), ""), SOURCE_MANUAL);
    }

    private String mergeTags(String rawTags, String scopeName, List<String> topicNames) {
        LinkedHashSet<String> tags = new LinkedHashSet<>(parseDelimited(rawTags));
        addTag(tags, scopeName);
        if (topicNames != null) {
            topicNames.forEach(topic -> addTag(tags, topic));
        }
        String merged = tags.stream()
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(TAG_LIMIT)
            .collect(Collectors.joining(","));
        return truncate(merged, 500);
    }

    private void addTag(Set<String> tags, String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim();
        if (StrUtil.isNotBlank(normalized)) {
            tags.add(normalized);
        }
    }

    private List<String> parseList(String raw) {
        if (StrUtil.isBlank(raw)) {
            return List.of();
        }
        try {
            List<String> values = objectMapper.readValue(raw, new TypeReference<>() { });
            return values == null ? List.of() : values.stream()
                .filter(StrUtil::isNotBlank)
                .map(String::trim)
                .toList();
        }
        catch (Exception ignored) {
            return parseDelimited(raw);
        }
    }

    private List<String> parseDelimited(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        return List.of(normalized.split("[,，;；]\\s*")).stream()
            .map(item -> item.replace("[", "").replace("]", "").replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private String leadingSentences(String text, int maxChars) {
        String normalized = StrUtil.blankToDefault(text, "").replaceAll("\\s+", " ").trim();
        if (normalized.isBlank()) {
            return "";
        }
        int limit = Math.min(normalized.length(), Math.max(1, maxChars));
        int sentenceCount = 0;
        int end = limit;
        for (int index = 0; index < limit; index++) {
            char current = normalized.charAt(index);
            if (current == '。' || current == '！' || current == '？' || current == '!' || current == '?' || current == ';' || current == '；') {
                sentenceCount++;
                if (sentenceCount >= 3) {
                    end = index + 1;
                    break;
                }
            }
        }
        return normalized.substring(0, end).trim();
    }

    private String summarizeScopeScores(List<ScopeScore> scores) {
        return scores.stream()
            .limit(5)
            .map(item -> item.scope().getScopeName() + ":" + round(item.score())
                + "(标题=" + round(item.titleSemantic())
                + ",章节=" + round(item.headingSemantic())
                + ",摘要=" + round(item.summarySemantic())
                + ",关键词=" + round(item.lexicalEvidence())
                + ",强证据=" + item.strongEvidence() + ")")
            .collect(Collectors.joining(", "));
    }

    private String summarizeTopicScores(List<TopicScore> scores) {
        return scores.stream()
            .limit(5)
            .map(item -> item.topic().getTopicName() + ":" + round(item.score()))
            .collect(Collectors.joining(", "));
    }

    private void refreshRouteIndex(Long documentId) {
        KnowledgeRouteIndexService service = routeIndexServiceProvider.getIfAvailable();
        if (service == null) {
            return;
        }
        try {
            service.refreshNow();
        }
        catch (Exception exception) {
            log.warn("文档分类写入后刷新知识路由索引失败，将在后续查询时重试: documentId={}", documentId, exception);
        }
    }

    private String join(String... values) {
        List<String> parts = new ArrayList<>();
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                parts.add(value.trim());
            }
        }
        return String.join("\n", parts);
    }

    private String joinDistinct(String... values) {
        Set<String> parts = new LinkedHashSet<>();
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                parts.add(value.trim());
            }
        }
        return String.join("\n", parts);
    }

    private String firstNonBlank(String... values) {
        for (String value : values) {
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String normalize(String text) {
        return StrUtil.blankToDefault(text, "")
            .replaceAll("[\\s>`*#_\\-，,。；;：:（）()“”\\\"'\\[\\]]+", "")
            .toLowerCase(Locale.ROOT);
    }

    private boolean containsHan(String value) {
        return value.codePoints().anyMatch(codePoint -> Character.UnicodeScript.of(codePoint) == Character.UnicodeScript.HAN);
    }

    private String truncate(String text, int maxLength) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.length() <= maxLength) {
            return normalized;
        }
        return normalized.substring(0, Math.max(0, maxLength));
    }

    private double clamp(double value) {
        return Math.max(0D, Math.min(1D, value));
    }

    private double round(double value) {
        return Math.round(value * 10000D) / 10000D;
    }

    private record ScopeScore(SuperAgentKnowledgeScopeNode scope,
                              double score,
                              double titleSemantic,
                              double headingSemantic,
                              double summarySemantic,
                              double lexicalEvidence,
                              boolean strongEvidence) {
    }

    private record ScopeDecision(ScopeScore top,
                                 double relativeGap,
                                 boolean accepted,
                                 String reason) {
    }

    private record TopicScore(SuperAgentKnowledgeTopicNode topic,
                              double score,
                              String reason) {
    }

    private record ItemScore(double score,
                             double semantic,
                             double lexical,
                             boolean aliasHit,
                             SectionEvidence evidence) {
    }

    private record SectionEvidence(String heading, String text) {
    }

    private record DocumentViews(String titleView,
                                 List<String> headingTexts,
                                 String summaryView,
                                 String keywordText) {
    }

    private record ScopeViews(String identityText,
                              String semanticText) {
    }

    private record ManualScopeResolution(SuperAgentKnowledgeScopeNode scope,
                                         boolean conflict,
                                         String reason) {
    }
}
