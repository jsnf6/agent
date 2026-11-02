package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import cn.hutool.core.collection.CollUtil;
import cn.hutool.core.util.StrUtil;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentDocumentStructureNode;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentProfileMapper;
import org.javaup.ai.manage.mapper.SuperAgentDocumentStructureNodeMapper;
import org.javaup.ai.manage.service.DocumentProfileService;
import org.javaup.ai.manage.service.DocumentKnowledgeBindingService;
import org.javaup.ai.manage.service.DocumentStorageService;
import org.javaup.ai.manage.support.DocumentAnalysisResult;
import org.javaup.ai.manage.support.DocumentTypeClassifier;
import org.javaup.enums.BusinessStatus;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Service;

import java.util.Collection;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
@Slf4j
@AllArgsConstructor
@Service
public class DocumentProfileServiceImpl implements DocumentProfileService {

    private static final int PROFILE_STATUS_SUCCESS = 2;

    private final SuperAgentDocumentMapper documentMapper;
    private final SuperAgentDocumentProfileMapper documentProfileMapper;
    private final SuperAgentDocumentStructureNodeMapper structureNodeMapper;
    private final DocumentStorageService storageService;
    private final DocumentKnowledgeBindingService documentKnowledgeBindingService;
    private final UidGenerator uidGenerator;

    @Override
    public SuperAgentDocumentProfile generateProfile(Long documentId,
                                                     DocumentAnalysisResult analysisResult,
                                                     List<SuperAgentDocumentStructureNode> structureNodes) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        String parsedText = analysisResult == null ? "" : StrUtil.blankToDefault(analysisResult.getParsedText(), "");
        List<SuperAgentDocumentStructureNode> safeNodes = structureNodes == null ? List.of() : structureNodes;
        DocumentProfileDraft draft = buildDraft(document, parsedText, safeNodes);

        SuperAgentDocumentProfile profile = documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1"));
        boolean creating = profile == null;
        if (creating) {
            profile = new SuperAgentDocumentProfile();
            profile.setId(uidGenerator.getUid());
            profile.setDocumentId(documentId);
            profile.setProfileVersion(1);
            profile.setStatus(BusinessStatus.YES.getCode());
        }
        else {
            profile.setProfileVersion(Optional.ofNullable(profile.getProfileVersion()).orElse(0) + 1);
        }
        profile.setDocumentSummary(draft.documentSummary());
        profile.setDocumentType(draft.documentType());
        profile.setCoreTopics(joinJsonLikeArray(draft.coreTopics()));
        profile.setExampleQuestions(joinJsonLikeArray(draft.exampleQuestions()));
        profile.setGraphFriendly(draft.graphFriendly() ? 1 : 0);
        profile.setSupportsGraphOutline(draft.supportsGraphOutline() ? 1 : 0);
        profile.setSupportsItemLookup(draft.supportsItemLookup() ? 1 : 0);
        profile.setSupportsGraphAssist(draft.supportsGraphAssist() ? 1 : 0);
        profile.setProfileSource("auto");
        profile.setProfileStatus(PROFILE_STATUS_SUCCESS);
        profile.setErrorMsg(null);
        if (creating) {
            documentProfileMapper.insert(profile);
        }
        else {
            documentProfileMapper.updateById(profile);
        }

        backfillDocumentMetadata(document, draft);
        log.info("文档画像生成完成: documentId={}, documentType={}, graphFriendly={}, supportsItemLookup={}, businessCategory='{}', tags='{}'",
            documentId,
            draft.documentType(),
            draft.graphFriendly(),
            draft.supportsItemLookup(),
            draft.businessCategory(),
            draft.documentTags());
        return profile;
    }

    @Override
    public Optional<SuperAgentDocumentProfile> getByDocumentId(Long documentId) {
        if (documentId == null) {
            return Optional.empty();
        }
        return Optional.ofNullable(documentProfileMapper.selectOne(new LambdaQueryWrapper<SuperAgentDocumentProfile>()
            .eq(SuperAgentDocumentProfile::getDocumentId, documentId)
            .eq(SuperAgentDocumentProfile::getStatus, BusinessStatus.YES.getCode())
            .last("LIMIT 1")));
    }

    @Override
    public SuperAgentDocumentProfile regenerateProfile(Long documentId) {
        if (documentId == null) {
            throw new IllegalArgumentException("documentId 不能为空");
        }
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null) {
            throw new IllegalArgumentException("文档不存在: " + documentId);
        }
        List<SuperAgentDocumentStructureNode> structureNodes = structureNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentDocumentStructureNode>()
            .eq(SuperAgentDocumentStructureNode::getDocumentId, documentId)
            .eq(SuperAgentDocumentStructureNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentDocumentStructureNode::getNodeNo, SuperAgentDocumentStructureNode::getId));
        String parsedText = loadParsedText(document, structureNodes);
        DocumentAnalysisResult analysisResult = new DocumentAnalysisResult();
        analysisResult.setParsedText(parsedText);
        SuperAgentDocumentProfile profile = generateProfile(documentId, analysisResult, structureNodes);
        documentKnowledgeBindingService.bind(documentId, analysisResult, structureNodes);
        return profile;
    }

    @Override
    public List<SuperAgentDocumentProfile> batchRegenerateProfiles(Collection<Long> documentIds) {
        if (documentIds == null || documentIds.isEmpty()) {
            return List.of();
        }
        List<SuperAgentDocumentProfile> profiles = new ArrayList<>();
        for (Long documentId : documentIds) {
            if (documentId == null) {
                continue;
            }
            profiles.add(regenerateProfile(documentId));
        }
        return profiles;
    }

    private DocumentProfileDraft buildDraft(SuperAgentDocument document,
                                            String parsedText,
                                            List<SuperAgentDocumentStructureNode> structureNodes) {
        List<String> sectionTitles = extractSectionTitles(structureNodes);
        boolean supportsItemLookup = structureNodes.stream().anyMatch(node -> node != null
            && (DocumentStructureNodeTypeEnum.STEP.getCode().equals(node.getNodeType())
            || DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType())));
        boolean supportsGraphOutline = sectionTitles.size() >= 2;
        boolean graphFriendly = supportsItemLookup || supportsGraphOutline;
        String documentType = DocumentTypeClassifier.classify(
            document.getDocumentName(),
            document.getOriginalFileName(),
            sectionTitles,
            parsedText,
            supportsItemLookup
        );
        List<String> coreTopics = buildCoreTopics(document, sectionTitles);
        List<String> exampleQuestions = buildExampleQuestions(documentType, coreTopics);
        String summary = buildSummary(document, sectionTitles, parsedText);
        String businessCategory = inferBusinessCategory(documentType, parsedText);
        String documentTags = buildDocumentTags(businessCategory, coreTopics);
        return new DocumentProfileDraft(
            summary,
            documentType,
            coreTopics,
            exampleQuestions,
            graphFriendly,
            supportsGraphOutline,
            supportsItemLookup,
            true,
            businessCategory,
            documentTags
        );
    }

    private void backfillDocumentMetadata(SuperAgentDocument document, DocumentProfileDraft draft) {
        boolean changed = false;
        if (StrUtil.isNotBlank(draft.businessCategory())
            && !Objects.equals(document.getBusinessCategory(), draft.businessCategory())) {
            document.setBusinessCategory(draft.businessCategory());
            changed = true;
        }
        if (StrUtil.isNotBlank(draft.documentTags())
            && !Objects.equals(document.getDocumentTags(), draft.documentTags())) {
            document.setDocumentTags(draft.documentTags());
            changed = true;
        }
        if (changed) {
            documentMapper.updateById(document);
        }
    }

    private List<String> extractSectionTitles(List<SuperAgentDocumentStructureNode> structureNodes) {
        if (CollUtil.isEmpty(structureNodes)) {
            return List.of();
        }
        return structureNodes.stream()
            .filter(node -> node != null && DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .map(SuperAgentDocumentStructureNode::getTitle)
            .filter(StrUtil::isNotBlank)
            .map(String::trim)
            .distinct()
            .limit(8)
            .toList();
    }

    private List<String> buildCoreTopics(SuperAgentDocument document, List<String> sectionTitles) {
        LinkedHashSet<String> topics = new LinkedHashSet<>();
        sectionTitles.stream().limit(6).forEach(title -> addTopic(topics, stripSectionCode(title)));
        addTopic(topics, stripFileExtension(document.getDocumentName()));
        return new ArrayList<>(topics).stream().filter(StrUtil::isNotBlank).limit(6).toList();
    }

    private void addTopic(Set<String> topics, String topic) {
        String normalized = StrUtil.blankToDefault(topic, "").trim();
        if (normalized.isBlank()) {
            return;
        }
        topics.add(normalized);
    }

    private List<String> buildExampleQuestions(String documentType, List<String> coreTopics) {
        List<String> examples = new ArrayList<>();
        for (String topic : coreTopics) {
            if ("troubleshooting".equals(documentType)) {
                examples.add(topic + "的可能原因有哪些？");
            }
            else if ("manual".equals(documentType)) {
                examples.add(topic + "的步骤是什么？");
            }
            else if ("rule".equals(documentType)) {
                examples.add(topic + "有哪些规则？");
            }
            else if ("faq".equals(documentType)) {
                examples.add(topic + "有哪些常见问题？");
            }
            else if ("spec".equals(documentType)) {
                examples.add(topic + "有哪些技术参数？");
            }
            else {
                examples.add(topic + "包括哪些内容？");
            }
        }
        return examples.stream().distinct().limit(6).toList();
    }

    private String buildSummary(SuperAgentDocument document, List<String> sectionTitles, String parsedText) {
        StringBuilder builder = new StringBuilder();
        builder.append("文档《").append(StrUtil.blankToDefault(document.getDocumentName(), "未命名文档")).append("》");
        if (!sectionTitles.isEmpty()) {
            builder.append("主要涵盖：").append(String.join("、", sectionTitles.stream().limit(4).toList())).append("。");
        }
        String excerpt = StrUtil.blankToDefault(parsedText, "").replaceAll("\\s+", " ").trim();
        if (excerpt.length() > 180) {
            excerpt = excerpt.substring(0, 180);
        }
        if (StrUtil.isNotBlank(excerpt)) {
            builder.append("摘要：").append(excerpt);
        }
        return builder.toString().trim();
    }

    private String inferBusinessCategory(String documentType, String parsedText) {
        if ("troubleshooting".equals(documentType)) {
            return "故障排查";
        }
        if ("rule".equals(documentType)) {
            return "规则";
        }
        if ("spec".equals(documentType)) {
            return "规格说明";
        }
        if ("manual".equals(documentType)) {
            return containsAny(parsedText.toLowerCase(Locale.ROOT), "步骤", "操作", "部署")
                ? "操作手册"
                : "手册";
        }
        if ("faq".equals(documentType)) {
            return "常见问题";
        }
        return "介绍";
    }

    private String buildDocumentTags(String businessCategory,
                                     List<String> coreTopics) {
        LinkedHashSet<String> tags = new LinkedHashSet<>();
        addTag(tags, businessCategory);
        coreTopics.stream().limit(4).forEach(topic -> addTag(tags, topic));
        return tags.stream()
            .map(String::trim)
            .filter(StrUtil::isNotBlank)
            .distinct()
            .limit(8)
            .collect(Collectors.joining(","));
    }

    private void addTag(Set<String> tags, String tag) {
        String normalized = StrUtil.blankToDefault(tag, "").trim();
        if (normalized.isBlank()) {
            return;
        }
        tags.add(normalized);
    }

    private boolean containsAny(String text, String... values) {
        String normalized = StrUtil.blankToDefault(text, "").toLowerCase(Locale.ROOT);
        for (String value : values) {
            if (normalized.contains(StrUtil.blankToDefault(value, "").toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }

    private String stripSectionCode(String title) {
        String normalized = StrUtil.blankToDefault(title, "").trim();
        return normalized.replaceFirst("^(第[一二三四五六七八九十百0-9]+[章节条部分]\\s*)|(\\d+(?:\\.\\d+)+\\s*)", "").trim();
    }

    private String loadParsedText(SuperAgentDocument document,
                                  List<SuperAgentDocumentStructureNode> structureNodes) {
        if (StrUtil.isNotBlank(document.getParseTextPath())) {
            try {
                String parsedText = storageService.downloadText(document.getParseTextPath());
                if (StrUtil.isNotBlank(parsedText)) {
                    return parsedText;
                }
            }
            catch (Exception exception) {
                log.warn("读取文档解析文本失败，回退到章节结构: documentId={}, parseTextPath={}",
                    document.getId(), document.getParseTextPath(), exception);
            }
        }
        StringBuilder builder = new StringBuilder();
        for (SuperAgentDocumentStructureNode node : structureNodes) {
            if (node == null || builder.length() >= 20000) {
                continue;
            }
            appendText(builder, node.getTitle());
            appendText(builder, node.getContentText());
        }
        return builder.toString().trim();
    }

    private void appendText(StringBuilder builder, String text) {
        if (StrUtil.isBlank(text)) {
            return;
        }
        if (builder.length() > 0) {
            builder.append('\n');
        }
        builder.append(text.trim());
    }

    private String stripFileExtension(String fileName) {
        String normalized = StrUtil.blankToDefault(fileName, "").trim();
        int index = normalized.lastIndexOf('.');
        return index > 0 ? normalized.substring(0, index) : normalized;
    }

    private String joinJsonLikeArray(List<String> values) {
        if (values == null || values.isEmpty()) {
            return "[]";
        }
        return values.stream()
            .map(value -> "\"" + value.replace("\"", "\\\"") + "\"")
            .collect(Collectors.joining(",", "[", "]"));
    }

    private record DocumentProfileDraft(
        String documentSummary,
        String documentType,
        List<String> coreTopics,
        List<String> exampleQuestions,
        boolean graphFriendly,
        boolean supportsGraphOutline,
        boolean supportsItemLookup,
        boolean supportsGraphAssist,
        String businessCategory,
        String documentTags
    ) {
    }
}
