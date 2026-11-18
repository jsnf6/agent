package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace;
import org.javaup.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.model.route.DocumentRouteCandidate;
import org.javaup.ai.manage.model.route.KnowledgeRouteDecision;
import org.javaup.ai.manage.model.route.ScopeRouteCandidate;
import org.javaup.ai.manage.model.route.TopicRouteCandidate;
import org.javaup.ai.manage.service.KnowledgeRouteIndexService;
import org.javaup.ai.manage.service.KnowledgeRouteService;
import org.javaup.ai.manage.support.KnowledgeRouteScoringSupport;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentIndexStatusEnum;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author:
 **/
@Slf4j
@AllArgsConstructor
@Service
public class KnowledgeRouteServiceImpl implements KnowledgeRouteService {

    private static final int ROUTE_STATUS_SUCCESS = 1;
    private static final int ROUTE_STATUS_LOW_CONFIDENCE = 2;
    private static final int ROUTE_STATUS_FAILED = 3;
    private static final int ROUTE_EMBEDDING_BATCH_SIZE = 10;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final DocumentManageProperties properties;
    private final ObjectProvider<EmbeddingModel> embeddingModelProvider;
    private final ObjectProvider<KnowledgeRouteIndexService> knowledgeRouteIndexServiceProvider;
    private final UidGenerator uidGenerator;

    @Override
    public KnowledgeRouteDecision route(String question, String rewriteQuestion) {
        //原问题，重写后的问题，分词（按照标点符号），做向量化
        RouteQueryContext queryContext = buildQueryContext(question, rewriteQuestion);
        KnowledgeRouteDecision decision = new KnowledgeRouteDecision();
        if (queryContext.queryTerms().isEmpty()) {
            decision.setRouteStatus("FAILED");
            decision.setReason("问题为空或无法提取有效关键词");
            return decision;
        }
        List<ScopeRouteCandidate> scopeCandidates = rankScopes(queryContext);
        List<TopicRouteCandidate> topicCandidates = rankTopics(queryContext, scopeCandidates);
        List<DocumentRouteCandidate> documentCandidates = rankDocuments(queryContext, scopeCandidates, topicCandidates);
        decision.setScopes(scopeCandidates);
        decision.setTopics(topicCandidates);
        decision.setDocuments(documentCandidates);
        BigDecimal confidence = resolveConfidence(documentCandidates, scopeCandidates, topicCandidates);
        decision.setConfidence(confidence);
        if (documentCandidates.isEmpty()) {
            decision.setRouteStatus("FAILED");
        }
        else if (confidence.compareTo(BigDecimal.valueOf(properties.getKnowledgeRoute().getSuccessThreshold())) < 0) {
            decision.setRouteStatus("LOW_CONFIDENCE");
        }
        else {
            decision.setRouteStatus("SUCCESS");
        }
        decision.setReason(documentCandidates.isEmpty()
            ? "没有找到可用候选文档"
            : resolveDecisionReason(documentCandidates, confidence));
        log.info("知识范围路由完成: question='{}', rewriteQuestion='{}', scopeCount={}, topicCount={}, documentCount={}, confidence={}, topDocument='{}'",
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            scopeCandidates.size(),
            topicCandidates.size(),
            documentCandidates.size(),
            confidence,
            documentCandidates.isEmpty() ? "" : documentCandidates.get(0).getDocumentName());
        return decision;
    }

    @Override
    public void recordShadowRoute(String conversationId,
                                  long exchangeId,
                                  Long selectedDocumentId,
                                  String question,
                                  String rewriteQuestion) {
        try {
            KnowledgeRouteDecision decision = route(question, rewriteQuestion);
            saveTrace(conversationId, exchangeId, selectedDocumentId, question, rewriteQuestion, "shadow", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由影子结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    @Override
    public void recordAutoRoute(String conversationId,
                                long exchangeId,
                                String question,
                                String rewriteQuestion,
                                KnowledgeRouteDecision decision) {
        try {
            Long selectedDocumentId = decision == null || decision.topDocument() == null || StrUtil.isBlank(decision.topDocument().getDocumentId())
                ? null
                : Long.valueOf(decision.topDocument().getDocumentId());
            saveTrace(conversationId, exchangeId, selectedDocumentId, question, rewriteQuestion, "auto", decision);
        }
        catch (Exception exception) {
            log.warn("记录知识路由 AUTO 结果失败: conversationId={}, exchangeId={}", conversationId, exchangeId, exception);
        }
    }

    private void saveTrace(String conversationId,
                           long exchangeId,
                           Long selectedDocumentId,
                           String question,
                           String rewriteQuestion,
                           String mode,
                           KnowledgeRouteDecision decision) {
        SuperAgentKnowledgeRouteTrace trace = new SuperAgentKnowledgeRouteTrace();
        trace.setId(uidGenerator.getUid());
        trace.setConversationId(conversationId);
        trace.setExchangeId(exchangeId);
        trace.setQuestion(question);
        trace.setRewriteQuestion(rewriteQuestion);
        trace.setMode(mode);
        trace.setTopScopesJson(writeScopeJson(decision == null ? List.of() : decision.getScopes()));
        trace.setTopTopicsJson(writeTopicJson(decision == null ? List.of() : decision.getTopics()));
        trace.setTopDocumentsJson(writeDocumentJson(decision == null ? List.of() : decision.getDocuments()));
        trace.setSelectedDocumentId(selectedDocumentId);
        trace.setHitSelectedDocument(resolveHitSelectedDocument(selectedDocumentId, decision));
        trace.setConfidence(decision == null ? BigDecimal.ZERO : decision.getConfidence());
        trace.setRouteStatus(resolveRouteStatus(decision));
        trace.setErrorMsg(decision == null ? "" : StrUtil.blankToDefault(decision.getReason(), ""));
        trace.setStatus(BusinessStatus.YES.getCode());
        knowledgeRouteTraceMapper.insert(trace);
    }

    private Integer resolveRouteStatus(KnowledgeRouteDecision decision) {
        if (decision == null) {
            return ROUTE_STATUS_FAILED;
        }
        return switch (StrUtil.blankToDefault(decision.getRouteStatus(), "FAILED")) {
            case "SUCCESS" -> ROUTE_STATUS_SUCCESS;
            case "LOW_CONFIDENCE" -> ROUTE_STATUS_LOW_CONFIDENCE;
            default -> ROUTE_STATUS_FAILED;
        };
    }

    private Integer resolveHitSelectedDocument(Long selectedDocumentId, KnowledgeRouteDecision decision) {
        if (selectedDocumentId == null || decision == null || decision.getDocuments() == null || decision.getDocuments().isEmpty()) {
            return null;
        }
        boolean hit = decision.getDocuments().stream()
            .limit(3)
            .anyMatch(item -> Objects.equals(String.valueOf(selectedDocumentId), item.getDocumentId()));
        return hit ? 1 : 0;
    }

    private List<ScopeRouteCandidate> rankScopes(RouteQueryContext queryContext) {
        List<SuperAgentKnowledgeScopeNode> nodes = scopeNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode()));
        if (nodes.isEmpty()) {
            return deriveScopesFromDocuments(queryContext);
        }
        DocumentManageProperties.KnowledgeRoute config = properties.getKnowledgeRoute();
        List<String> routeTexts = nodes.stream()
            .map(this::buildScopeRouteText)
            .toList();
        List<Double> cosineScores = computeSemanticScores(queryContext, routeTexts);
        boolean semanticAvailable = queryContext.semanticEnabled() && cosineScores.size() == nodes.size();
        Map<String, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "scope", 5).stream()
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityCode, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<ScopeRouteCandidate> candidates = new ArrayList<>(nodes.size());
        for (int index = 0; index < nodes.size(); index++) {
            SuperAgentKnowledgeScopeNode node = nodes.get(index);
            String routeText = routeTexts.get(index);
            double semanticScore = semanticAvailable
                ? KnowledgeRouteScoringSupport.semanticUnit(
                    cosineScores.get(index),
                    config.getCosineFloor(),
                    config.getCosineCeil())
                : 0D;
            KnowledgeRouteScoringSupport.LexicalEvidence localLexical = KnowledgeRouteScoringSupport.lexicalEvidence(
                queryContext.routingText(),
                routeText,
                identityAliases(node.getScopeName(), node.getAliases())
            );
            double externalLexical = KnowledgeRouteScoringSupport.normalizedExternalLexical(
                lexicalScores.getOrDefault(node.getScopeCode(), 0D),
                config.getEsLexicalSaturation()
            );
            double lexicalScore = Math.max(localLexical.score(), externalLexical);
            double score = KnowledgeRouteScoringSupport.weightedScore(
                new double[]{
                    semanticAvailable ? config.getScopeSemanticWeight() : 0D,
                    config.getScopeLexicalWeight()
                },
                new double[]{semanticScore, lexicalScore}
            );
            if (score <= 0D) {
                continue;
            }
            candidates.add(new ScopeRouteCandidate(
                node.getScopeCode(),
                node.getScopeName(),
                scoreToBigDecimal(score),
                "语义=" + round(semanticScore)
                    + "；词面=" + round(lexicalScore)
                    + (localLexical.aliasHit() ? "；命中别名=" + String.join("、", localLexical.matches()) : "")
            ));
        }
        return candidates.stream()
            .sorted(Comparator.comparing(ScopeRouteCandidate::getScore).reversed())
            .limit(config.getScopeCandidateLimit())
            .toList();
    }

    private List<ScopeRouteCandidate> deriveScopesFromDocuments(RouteQueryContext queryContext) {
        List<SuperAgentDocument> documents = listRetrievableDocuments();
        DocumentManageProperties.KnowledgeRoute config = properties.getKnowledgeRoute();
        boolean semanticAvailable = queryContext.semanticEnabled();
        Map<String, ScopeAccumulator> accumulatorMap = new LinkedHashMap<>();
        for (SuperAgentDocument document : documents) {
            if (StrUtil.isBlank(document.getKnowledgeScopeCode()) && StrUtil.isBlank(document.getKnowledgeScopeName())) {
                continue;
            }
            String code = firstNonBlank(document.getKnowledgeScopeCode(), "general_document");
            String name = firstNonBlank(document.getKnowledgeScopeName(), "通用文档");
            String routeText = join(name, document.getBusinessCategory(), document.getDocumentTags());
            double semanticScore = semanticScore(queryContext, routeText);
            double semanticUnit = semanticUnit(semanticScore, config);
            KnowledgeRouteScoringSupport.LexicalEvidence lexicalEvidence = KnowledgeRouteScoringSupport.lexicalEvidence(
                queryContext.routingText(),
                routeText,
                identityAliases(name, document.getDocumentTags())
            );
            double score = KnowledgeRouteScoringSupport.weightedScore(
                new double[]{
                    semanticAvailable ? config.getScopeSemanticWeight() : 0D,
                    config.getScopeLexicalWeight()
                },
                new double[]{semanticUnit, lexicalEvidence.score()}
            );
            ScopeAccumulator accumulator = accumulatorMap.computeIfAbsent(code, key -> new ScopeAccumulator(code, name));
            if (score > accumulator.maxScore) {
                accumulator.maxScore = score;
                accumulator.reason = "由文档元数据兜底推导；语义=" + round(semanticUnit)
                    + "；词面=" + round(lexicalEvidence.score());
            }
        }
        return accumulatorMap.values().stream()
            .filter(item -> item.maxScore > 0D)
            .map(item -> new ScopeRouteCandidate(item.scopeCode, item.scopeName, scoreToBigDecimal(item.maxScore), item.reason))
            .sorted(Comparator.comparing(ScopeRouteCandidate::getScore).reversed())
            .limit(config.getScopeCandidateLimit())
            .toList();
    }

    private List<TopicRouteCandidate> rankTopics(RouteQueryContext queryContext, List<ScopeRouteCandidate> scopeCandidates) {
        if (scopeCandidates == null || scopeCandidates.isEmpty()) {
            return List.of();
        }
        List<SuperAgentKnowledgeTopicNode> nodes = topicNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));
        if (nodes.isEmpty()) {
            return List.of();
        }
        DocumentManageProperties.KnowledgeRoute config = properties.getKnowledgeRoute();
        List<ScopeRouteCandidate> scopedCandidates = scopeCandidates.stream()
            .limit(config.getTopicScopeLimit())
            .toList();
        Map<String, ScopeRouteCandidate> parentScopeMap = scopedCandidates.stream()
            .collect(Collectors.toMap(ScopeRouteCandidate::getScopeCode, item -> item, (left, right) -> left));
        List<SuperAgentKnowledgeTopicNode> eligibleTopics = nodes.stream()
            .filter(topic -> parentScopeMap.containsKey(topic.getScopeCode()))
            .toList();
        if (eligibleTopics.isEmpty()) {
            return List.of();
        }
        Map<String, String> scopeNameMap = scopeCandidates.stream()
            .collect(Collectors.toMap(ScopeRouteCandidate::getScopeCode, ScopeRouteCandidate::getScopeName, (left, right) -> left));
        List<String> routeTexts = eligibleTopics.stream()
            .map(topic -> buildTopicRouteText(topic, scopeNameMap.get(topic.getScopeCode())))
            .toList();
        List<Double> cosineScores = computeSemanticScores(queryContext, routeTexts);
        boolean semanticAvailable = queryContext.semanticEnabled() && cosineScores.size() == eligibleTopics.size();
        Map<String, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "topic", 8).stream()
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::entityCode, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<TopicRouteCandidate> candidates = new ArrayList<>(eligibleTopics.size());
        for (int index = 0; index < eligibleTopics.size(); index++) {
            SuperAgentKnowledgeTopicNode node = eligibleTopics.get(index);
            String routeText = routeTexts.get(index);
            double semanticScore = semanticAvailable
                ? semanticUnit(cosineScores.get(index), config)
                : 0D;
            KnowledgeRouteScoringSupport.LexicalEvidence localLexical = KnowledgeRouteScoringSupport.lexicalEvidence(
                queryContext.routingText(),
                routeText,
                identityAliases(node.getTopicName(), node.getAliases())
            );
            double externalLexical = KnowledgeRouteScoringSupport.normalizedExternalLexical(
                lexicalScores.getOrDefault(node.getTopicCode(), 0D),
                config.getEsLexicalSaturation()
            );
            double lexicalScore = Math.max(localLexical.score(), externalLexical);
            double ownScore = KnowledgeRouteScoringSupport.weightedScore(
                new double[]{
                    semanticAvailable ? config.getTopicSemanticWeight() : 0D,
                    config.getTopicLexicalWeight()
                },
                new double[]{semanticScore, lexicalScore}
            );
            ScopeRouteCandidate parentScope = parentScopeMap.get(node.getScopeCode());
            double parentScore = parentScope == null ? 0D : parentScope.getScore().doubleValue();
            double score = KnowledgeRouteScoringSupport.weightedScore(
                new double[]{config.getTopicOwnWeight(), config.getTopicParentWeight()},
                new double[]{ownScore, parentScore}
            );
            if (score <= 0D) {
                continue;
            }
            candidates.add(new TopicRouteCandidate(
                node.getTopicCode(),
                node.getTopicName(),
                node.getScopeCode(),
                scoreToBigDecimal(score),
                "主题自身=" + round(ownScore)
                    + "；父scope=" + round(parentScore)
                    + "；语义=" + round(semanticScore)
                    + "；词面=" + round(lexicalScore)
                    + (localLexical.aliasHit() ? "；命中别名=" + String.join("、", localLexical.matches()) : "")
            ));
        }
        return candidates.stream()
            .sorted(Comparator.comparing(TopicRouteCandidate::getScore).reversed())
            .limit(config.getTopicCandidateLimit())
            .toList();
    }

    private List<DocumentRouteCandidate> rankDocuments(RouteQueryContext queryContext,
                                                       List<ScopeRouteCandidate> scopeCandidates,
                                                       List<TopicRouteCandidate> topicCandidates) {
        List<SuperAgentDocument> allDocuments = listRetrievableDocuments();
        if (allDocuments.isEmpty()) {
            return List.of();
        }
        DocumentManageProperties.KnowledgeRoute config = properties.getKnowledgeRoute();
        List<ScopeRouteCandidate> scopedCandidates = scopeCandidates == null
            ? List.of()
            : scopeCandidates.stream().limit(config.getDocumentScopeLimit()).toList();
        Map<String, Double> scopeScoreMap = scopedCandidates.stream()
            .collect(Collectors.toMap(ScopeRouteCandidate::getScopeCode,
                item -> item.getScore().doubleValue(), (left, right) -> left));
        double topScopeScore = scopedCandidates.isEmpty() ? 0D : scopedCandidates.get(0).getScore().doubleValue();
        List<SuperAgentDocument> documents = scopeScoreMap.isEmpty()
            ? allDocuments
            : allDocuments.stream()
                .filter(document -> scopeScoreMap.containsKey(StrUtil.blankToDefault(document.getKnowledgeScopeCode(), "")))
                .toList();
        boolean scopeFallback = false;
        if (documents.isEmpty()) {
            documents = allDocuments;
            scopeFallback = true;
        }
        Map<Long, SuperAgentDocumentProfile> profileMap = documentProfileMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
                .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
                .eq(SuperAgentDocumentProfile::getProfileStatus, 2))
            .stream()
            .collect(Collectors.toMap(SuperAgentDocumentProfile::getDocumentId, item -> item, (left, right) -> right));
        List<SuperAgentKnowledgeTopicNode> allTopics = topicNodeMapper.selectList(
            new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
                .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));
        Map<String, SuperAgentKnowledgeTopicNode> topicMap = allTopics.stream()
            .collect(Collectors.toMap(SuperAgentKnowledgeTopicNode::getTopicCode, item -> item, (left, right) -> left));
        Map<Long, String> documentScopeMap = allDocuments.stream()
            .collect(Collectors.toMap(SuperAgentDocument::getId,
                item -> StrUtil.blankToDefault(item.getKnowledgeScopeCode(), ""), (left, right) -> left));
        Map<String, Map<Long, SuperAgentTopicDocumentRelation>> relationByTopic = topicDocumentRelationMapper.selectList(
                new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
                    .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode()))
            .stream()
            .collect(Collectors.groupingBy(SuperAgentTopicDocumentRelation::getTopicCode,
                Collectors.toMap(SuperAgentTopicDocumentRelation::getDocumentId, item -> item, (left, right) -> right)));
        Map<Long, List<String>> topicNamesByDocument = new LinkedHashMap<>();
        relationByTopic.forEach((topicCode, relations) -> {
            SuperAgentKnowledgeTopicNode topic = topicMap.get(topicCode);
            if (topic == null || StrUtil.isBlank(topic.getTopicName())) {
                return;
            }
            relations.keySet().forEach(documentId -> {
                String documentScope = documentScopeMap.get(documentId);
                if (StrUtil.isNotBlank(documentScope) && !Objects.equals(documentScope, topic.getScopeCode())) {
                    return;
                }
                topicNamesByDocument.computeIfAbsent(documentId, ignored -> new ArrayList<>()).add(topic.getTopicName());
            });
        });
        Map<Long, Double> topicRelevanceByDocument = buildTopicRelevanceByDocument(
            topicCandidates,
            relationByTopic,
            documentScopeMap
        );
        List<DocumentRouteMaterial> materials = documents.stream()
            .map(document -> buildDocumentRouteMaterial(
                document,
                profileMap.get(document.getId()),
                topicNamesByDocument.getOrDefault(document.getId(), List.of())
            ))
            .toList();
        List<Double> cosineScores = computeSemanticScores(queryContext, materials.stream().map(DocumentRouteMaterial::routeText).toList());
        boolean semanticAvailable = queryContext.semanticEnabled() && cosineScores.size() == materials.size();
        Map<Long, Double> lexicalScores = searchLexicalScores(queryContext.routingText(), "document", 10).stream()
            .filter(hit -> hit.documentId() != null)
            .collect(Collectors.toMap(KnowledgeRouteIndexService.RouteLexicalHit::documentId, KnowledgeRouteIndexService.RouteLexicalHit::score, (left, right) -> left));
        List<DocumentRouteCandidate> candidates = new ArrayList<>(documents.size());
        for (int index = 0; index < documents.size(); index++) {
            SuperAgentDocument document = documents.get(index);
            DocumentRouteMaterial material = materials.get(index);
            double semanticScore = semanticAvailable
                ? semanticUnit(cosineScores.get(index), config)
                : 0D;
            KnowledgeRouteScoringSupport.LexicalEvidence localLexical = KnowledgeRouteScoringSupport.lexicalEvidence(
                queryContext.routingText(),
                material.routeText(),
                material.aliases()
            );
            double externalLexical = KnowledgeRouteScoringSupport.normalizedExternalLexical(
                lexicalScores.getOrDefault(document.getId(), 0D),
                config.getEsLexicalSaturation()
            );
            double lexicalScore = Math.max(localLexical.score(), externalLexical);
            double documentScopeScore = scopeScoreMap.getOrDefault(
                StrUtil.blankToDefault(document.getKnowledgeScopeCode(), ""), 0D);
            double scopeRelevance = topScopeScore <= 0D ? 0D : documentScopeScore / topScopeScore;
            double topicRelevance = topicRelevanceByDocument.getOrDefault(document.getId(), 0D);
            double score = KnowledgeRouteScoringSupport.weightedScore(
                new double[]{
                    semanticAvailable ? config.getDocumentSemanticWeight() : 0D,
                    config.getDocumentLexicalWeight(),
                    scopedCandidates.isEmpty() ? 0D : config.getDocumentScopeWeight(),
                    topicCandidates == null || topicCandidates.isEmpty() ? 0D : config.getDocumentTopicWeight()
                },
                new double[]{semanticScore, lexicalScore, scopeRelevance, topicRelevance}
            );
            if (score <= 0D) {
                continue;
            }
            candidates.add(new DocumentRouteCandidate(
                String.valueOf(document.getId()),
                document.getDocumentName(),
                document.getLastIndexTaskId() == null ? "" : String.valueOf(document.getLastIndexTaskId()),
                StrUtil.blankToDefault(document.getKnowledgeScopeCode(), ""),
                StrUtil.blankToDefault(document.getKnowledgeScopeName(), ""),
                StrUtil.blankToDefault(document.getBusinessCategory(), ""),
                StrUtil.blankToDefault(document.getDocumentTags(), ""),
                scoreToBigDecimal(score),
                "语义=" + round(semanticScore)
                    + "；词面=" + round(lexicalScore)
                    + "；知识域对齐=" + round(scopeRelevance)
                    + "；主题关联=" + round(topicRelevance)
                    + (localLexical.aliasHit() ? "；命中别名=" + String.join("、", localLexical.matches()) : "")
                    + (scopeFallback ? "；候选知识域无文档，已回退全局语义" : "")
            ));
        }
        return candidates.stream()
            .sorted(Comparator.comparing(DocumentRouteCandidate::getScore).reversed())
            .limit(config.getDocumentCandidateLimit())
            .toList();
    }

    private RouteQueryContext buildQueryContext(String question, String rewriteQuestion) {
        String routingText = buildRoutingText(question, rewriteQuestion);
        List<String> queryTerms = tokenize(routingText);
        float[] queryEmbedding = embedSingle(routingText);
        return new RouteQueryContext(
            StrUtil.blankToDefault(question, ""),
            StrUtil.blankToDefault(rewriteQuestion, ""),
            routingText,
            queryTerms,
            queryEmbedding
        );
    }

    private String buildRoutingText(String question, String rewriteQuestion) {
        String original = StrUtil.blankToDefault(question, "").trim();
        String rewritten = StrUtil.blankToDefault(rewriteQuestion, "").trim();
        if (StrUtil.isBlank(original)) {
            return rewritten;
        }
        if (StrUtil.isBlank(rewritten) || Objects.equals(original, rewritten)) {
            return original;
        }
        return original + " " + rewritten;
    }

    private String buildScopeRouteText(SuperAgentKnowledgeScopeNode scope) {
        return join(
            scope.getScopeName(),
            scope.getDescription(),
            scope.getAliases(),
            scope.getExamples()
        );
    }

    private String buildTopicRouteText(SuperAgentKnowledgeTopicNode topic, String parentScopeName) {
        return join(
            parentScopeName,
            topic.getTopicName(),
            topic.getDescription(),
            topic.getAliases(),
            topic.getExamples()
        );
    }

    private DocumentRouteMaterial buildDocumentRouteMaterial(SuperAgentDocument document,
                                                             SuperAgentDocumentProfile profile,
                                                             List<String> topicNames) {
        List<String> aliases = new ArrayList<>();
        aliases.add(document.getDocumentName());
        aliases.addAll(parseList(document.getDocumentTags()));
        aliases.addAll(topicNames);
        return new DocumentRouteMaterial(
            document.getId(),
            join(
                document.getDocumentName(),
                document.getKnowledgeScopeName(),
                document.getDocumentTags(),
                profile == null ? "" : profile.getDocumentSummary(),
                profile == null ? "" : profile.getCoreTopics(),
                String.join(" ", topicNames)
            ),
            aliases.stream().filter(StrUtil::isNotBlank).distinct().toList()
        );
    }

    private Map<Long, Double> buildTopicRelevanceByDocument(
        List<TopicRouteCandidate> topicCandidates,
        Map<String, Map<Long, SuperAgentTopicDocumentRelation>> relationByTopic,
        Map<Long, String> documentScopeMap) {
        if (topicCandidates == null || topicCandidates.isEmpty()) {
            return Map.of();
        }
        Map<Long, Double> relevanceMap = new LinkedHashMap<>();
        for (TopicRouteCandidate topic : topicCandidates) {
            Map<Long, SuperAgentTopicDocumentRelation> relations = relationByTopic.get(topic.getTopicCode());
            if (relations == null || relations.isEmpty()) {
                continue;
            }
            double topicScore = topic.getScore() == null ? 0D : topic.getScore().doubleValue();
            for (Map.Entry<Long, SuperAgentTopicDocumentRelation> entry : relations.entrySet()) {
                String documentScope = documentScopeMap.get(entry.getKey());
                if (StrUtil.isNotBlank(documentScope) && !Objects.equals(documentScope, topic.getScopeCode())) {
                    continue;
                }
                SuperAgentTopicDocumentRelation relation = entry.getValue();
                double relationScore = relation == null || relation.getRelationScore() == null
                    ? 0D
                    : KnowledgeRouteScoringSupport.clamp(relation.getRelationScore().doubleValue());
                double relevance = relationScore * (0.70D + 0.30D * topicScore);
                relevanceMap.merge(entry.getKey(), relevance, Math::max);
            }
        }
        return relevanceMap;
    }

    private double semanticUnit(double similarity, DocumentManageProperties.KnowledgeRoute config) {
        return KnowledgeRouteScoringSupport.semanticUnit(
            similarity,
            config.getCosineFloor(),
            config.getCosineCeil()
        );
    }

    private BigDecimal resolveConfidence(List<DocumentRouteCandidate> documents,
                                         List<ScopeRouteCandidate> scopes,
                                         List<TopicRouteCandidate> topics) {
        if (documents == null || documents.isEmpty()) {
            return BigDecimal.ZERO;
        }
        DocumentManageProperties.KnowledgeRoute config = properties.getKnowledgeRoute();
        double top = documents.get(0).getScore().doubleValue();
        double second = documents.size() > 1 ? documents.get(1).getScore().doubleValue() : 0D;
        double gapRatio = KnowledgeRouteScoringSupport.marginRatio(top, second);
        double topScope = scopes == null || scopes.isEmpty() ? 0D : scopes.get(0).getScore().doubleValue();
        double topTopic = topics == null || topics.isEmpty() ? 0D : topics.get(0).getScore().doubleValue();
        double confidence = config.getConfidenceDocumentWeight() * top
            + config.getConfidenceGapWeight() * gapRatio
            + config.getConfidenceScopeWeight() * topScope
            + config.getConfidenceTopicWeight() * topTopic;
        return scoreToBigDecimal(KnowledgeRouteScoringSupport.clamp(confidence));
    }

    private List<SuperAgentDocument> listRetrievableDocuments() {
        return documentMapper.selectList(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
            .eq(SuperAgentDocument::getIndexStatus, DocumentIndexStatusEnum.BUILD_SUCCESS.getCode())
            .isNotNull(SuperAgentDocument::getLastIndexTaskId)
            .orderByAsc(SuperAgentDocument::getId));
    }

    private List<KnowledgeRouteIndexService.RouteLexicalHit> searchLexicalScores(String routingText, String entityType, int size) {
        KnowledgeRouteIndexService routeIndexService = knowledgeRouteIndexServiceProvider.getIfAvailable();
        if (routeIndexService == null) {
            return List.of();
        }
        return routeIndexService.search(routingText, entityType, size);
    }

    private List<String> tokenize(String text) {
        String normalized = StrUtil.blankToDefault(text, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        LinkedHashSet<String> terms = new LinkedHashSet<>();
        for (String segment : normalized.split("[\\s、，,；;：:（）()\\-的和及与或]+")) {
            String trimmed = segment.trim();
            if (trimmed.length() >= 2) {
                terms.add(trimmed);
                expandChineseNgrams(terms, trimmed);
            }
        }
        return new ArrayList<>(terms).stream().limit(40).toList();
    }

    private List<String> parseJsonArray(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank() || "[]".equals(normalized)) {
            return List.of();
        }
        String body = normalized.replace("[", "").replace("]", "");
        if (body.isBlank()) {
            return List.of();
        }
        return List.of(body.split(",")).stream()
            .map(item -> item.replace("\"", "").trim())
            .filter(StrUtil::isNotBlank)
            .toList();
    }

    private List<String> parseList(String raw) {
        String normalized = StrUtil.blankToDefault(raw, "").trim();
        if (normalized.isBlank()) {
            return List.of();
        }
        if (normalized.startsWith("[")) {
            return parseJsonArray(normalized);
        }
        return Arrays.stream(normalized.split("[,，、;；\\n]+"))
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .toList();
    }

    private List<String> identityAliases(String name, String rawAliases) {
        List<String> aliases = new ArrayList<>();
        if (StrUtil.isNotBlank(name)) {
            aliases.add(name.trim());
        }
        aliases.addAll(parseList(rawAliases));
        return aliases.stream().filter(StrUtil::isNotBlank).distinct().toList();
    }

    private BigDecimal scoreToBigDecimal(double score) {
        return BigDecimal.valueOf(score).setScale(4, RoundingMode.HALF_UP);
    }

    private double round(double value) {
        return Math.round(KnowledgeRouteScoringSupport.clamp(value) * 10000D) / 10000D;
    }

    private String join(String... values) {
        return Arrays.stream(values)
            .filter(StrUtil::isNotBlank)
            .collect(Collectors.joining(" "));
    }

    private String normalize(String value) {
        return KnowledgeRouteScoringSupport.normalize(value);
    }

    private String firstNonBlank(String primary, String fallback) {
        if (StrUtil.isNotBlank(primary)) {
            return primary.trim();
        }
        return StrUtil.blankToDefault(fallback, "");
    }

    private float[] embedSingle(String text) {
        if (StrUtil.isBlank(text)) {
            return null;
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return null;
        }
        try {
            return embeddingModel.embed(text.trim());
        }
        catch (Exception exception) {
            log.warn("知识路由生成问题向量失败，退回词面匹配: text='{}'", StrUtil.maxLength(text, 120), exception);
            return null;
        }
    }

    private List<Double> computeSemanticScores(RouteQueryContext queryContext, List<String> routeTexts) {
        if (!queryContext.semanticEnabled() || routeTexts == null || routeTexts.isEmpty()) {
            return routeTexts == null ? List.of() : routeTexts.stream().map(item -> 0D).toList();
        }
        EmbeddingModel embeddingModel = embeddingModelProvider.getIfAvailable();
        if (embeddingModel == null) {
            return routeTexts.stream().map(item -> 0D).toList();
        }
        try {
            List<String> normalizedRouteTexts = routeTexts.stream()
                .map(item -> StrUtil.blankToDefault(item, ""))
                .toList();
            List<Double> scores = new ArrayList<>(normalizedRouteTexts.size());
            for (int index = 0; index < normalizedRouteTexts.size(); index++) {
                scores.add(0D);
            }
            int totalBatchCount = (normalizedRouteTexts.size() + ROUTE_EMBEDDING_BATCH_SIZE - 1) / ROUTE_EMBEDDING_BATCH_SIZE;
            for (int startIndex = 0; startIndex < normalizedRouteTexts.size(); startIndex += ROUTE_EMBEDDING_BATCH_SIZE) {
                int endIndex = Math.min(startIndex + ROUTE_EMBEDDING_BATCH_SIZE, normalizedRouteTexts.size());
                List<String> currentBatch = normalizedRouteTexts.subList(startIndex, endIndex);
                int currentBatchIndex = (startIndex / ROUTE_EMBEDDING_BATCH_SIZE) + 1;
                log.debug("知识路由候选向量分批计算: batchIndex={}/{}, candidateRange=[{}, {}], batchSize={}",
                    currentBatchIndex, totalBatchCount, startIndex + 1, endIndex, currentBatch.size());
                List<float[]> embeddings = embeddingModel.embed(currentBatch);
                if (embeddings == null || embeddings.size() != currentBatch.size()) {
                    return routeTexts.stream().map(item -> 0D).toList();
                }
                for (int batchIndex = 0; batchIndex < embeddings.size(); batchIndex++) {
                    scores.set(startIndex + batchIndex, cosineSimilarity(queryContext.queryEmbedding(), embeddings.get(batchIndex)));
                }
            }
            return scores;
        }
        catch (Exception exception) {
            log.warn("知识路由生成候选向量失败，退回词面匹配: candidateCount={}", routeTexts.size(), exception);
            return routeTexts.stream().map(item -> 0D).toList();
        }
    }

    private double semanticScore(RouteQueryContext queryContext, String routeText) {
        if (!queryContext.semanticEnabled()) {
            return 0D;
        }
        float[] routeEmbedding = embedSingle(routeText);
        if (routeEmbedding == null) {
            return 0D;
        }
        return cosineSimilarity(queryContext.queryEmbedding(), routeEmbedding);
    }

    private double cosineSimilarity(float[] left, float[] right) {
        return KnowledgeRouteScoringSupport.cosineSimilarity(left, right);
    }

    private void expandChineseNgrams(Set<String> terms, String segment) {
        String normalized = segment.trim();
        if (normalized.length() < 4) {
            return;
        }
        int maxGramLength = Math.min(6, normalized.length());
        for (int gramLength = 2; gramLength <= maxGramLength; gramLength++) {
            for (int start = 0; start + gramLength <= normalized.length(); start++) {
                String gram = normalized.substring(start, start + gramLength);
                if (gram.length() >= 2) {
                    terms.add(gram);
                }
            }
        }
    }

    private String resolveDecisionReason(List<DocumentRouteCandidate> documentCandidates, BigDecimal confidence) {
        if (documentCandidates == null || documentCandidates.isEmpty()) {
            return "没有找到可用候选文档";
        }
        String topReason = StrUtil.blankToDefault(documentCandidates.get(0).getReason(), "");
        if (confidence == null) {
            return topReason;
        }
        if (confidence.compareTo(BigDecimal.valueOf(properties.getKnowledgeRoute().getSuccessThreshold())) < 0) {
            return StrUtil.blankToDefault(topReason, "低置信度，已进入保守扩范围候选");
        }
        return topReason;
    }

    private String writeScopeJson(List<ScopeRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"scopeCode\":\"" + item.getScopeCode() + "\",\"scopeName\":\"" + item.getScopeName() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeTopicJson(List<TopicRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"topicCode\":\"" + item.getTopicCode() + "\",\"topicName\":\"" + item.getTopicName() + "\",\"scopeCode\":\"" + item.getScopeCode() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String writeDocumentJson(List<DocumentRouteCandidate> candidates) {
        return candidates == null || candidates.isEmpty() ? "[]" : candidates.stream()
            .map(item -> "{\"documentId\":\"" + item.getDocumentId() + "\",\"documentName\":\"" + escapeJson(item.getDocumentName()) + "\",\"lastIndexTaskId\":\"" + item.getLastIndexTaskId() + "\",\"score\":\"" + item.getScore() + "\",\"reason\":\"" + escapeJson(item.getReason()) + "\"}")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private String escapeJson(String text) {
        return StrUtil.blankToDefault(text, "").replace("\"", "\\\"");
    }

    private static final class ScopeAccumulator {
        private final String scopeCode;
        private final String scopeName;
        private double maxScore;
        private String reason = "";

        private ScopeAccumulator(String scopeCode, String scopeName) {
            this.scopeCode = scopeCode;
            this.scopeName = scopeName;
        }
    }

    private record RouteQueryContext(String originalQuestion,
                                     String rewriteQuestion,
                                     String routingText,
                                     List<String> queryTerms,
                                     float[] queryEmbedding) {
        private boolean semanticEnabled() {
            return queryEmbedding != null && queryEmbedding.length > 0;
        }
    }

    private record DocumentRouteMaterial(Long documentId,
                                         String routeText,
                                         List<String> aliases) {
    }
}
