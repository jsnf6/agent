package org.javaup.ai.manage.service.impl;

import lombok.AllArgsConstructor;
import com.baidu.fsg.uid.UidGenerator;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import cn.hutool.core.util.StrUtil;
import org.javaup.ai.manage.data.SuperAgentDocument;
import org.javaup.ai.manage.data.SuperAgentDocumentProfile;
import org.javaup.ai.manage.data.SuperAgentKnowledgeScopeNode;
import org.javaup.ai.manage.data.SuperAgentKnowledgeTopicNode;
import org.javaup.ai.manage.data.SuperAgentTopicDocumentRelation;
import org.javaup.ai.manage.dto.DocumentProfileBatchRegenerateDto;
import org.javaup.ai.manage.dto.DocumentProfileDetailQueryDto;
import org.javaup.ai.manage.dto.DocumentProfileRegenerateDto;
import org.javaup.ai.manage.dto.KnowledgeRouteTraceQueryDto;
import org.javaup.ai.manage.dto.KnowledgeScopeDeleteDto;
import org.javaup.ai.manage.dto.KnowledgeScopeSaveDto;
import org.javaup.ai.manage.dto.KnowledgeTopicDeleteDto;
import org.javaup.ai.manage.dto.KnowledgeTopicQueryDto;
import org.javaup.ai.manage.dto.KnowledgeTopicSaveDto;
import org.javaup.ai.manage.dto.TopicDocumentRelationListQueryDto;
import org.javaup.ai.manage.dto.TopicDocumentRelationRemoveDto;
import org.javaup.ai.manage.dto.TopicDocumentRelationSaveDto;
import org.javaup.ai.manage.mapper.SuperAgentDocumentMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeScopeNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeTopicNodeMapper;
import org.javaup.ai.manage.mapper.SuperAgentKnowledgeRouteTraceMapper;
import org.javaup.ai.manage.mapper.SuperAgentTopicDocumentRelationMapper;
import org.javaup.ai.manage.service.DocumentProfileService;
import org.javaup.ai.manage.service.KnowledgeManageService;
import org.javaup.ai.manage.vo.DocumentProfileVo;
import org.javaup.ai.manage.vo.KnowledgeRouteTraceItemVo;
import org.javaup.ai.manage.vo.KnowledgeRouteTracePageVo;
import org.javaup.ai.manage.vo.KnowledgeScopeItemVo;
import org.javaup.ai.manage.vo.KnowledgeTopicItemVo;
import org.javaup.ai.manage.vo.TopicDocumentRelationItemVo;
import org.javaup.enums.BaseCode;
import org.javaup.enums.BusinessStatus;
import org.javaup.exception.SuperAgentFrameException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 服务实现层
 * @author: 阿星不是程序员
 **/
@AllArgsConstructor
@Service
public class KnowledgeManageServiceImpl implements KnowledgeManageService {

    private static final Pattern CODE_PATTERN = Pattern.compile("^[A-Za-z][A-Za-z0-9_-]{0,63}$");

    private static final int MAX_NAME_LENGTH = 128;

    private static final int MAX_DESCRIPTION_LENGTH = 1024;

    private static final int MAX_ALIASES_LENGTH = 512;

    private static final int MAX_EXAMPLES_LENGTH = 10000;

    private static final int MAX_PARENT_SCOPE_DEPTH = 32;

    private static final Set<String> ANSWER_SHAPES = Set.of("explain", "list", "steps", "compare", "structure");

    private static final Set<String> EXECUTION_PREFERENCES = Set.of(
        "retrieval", "graph_only", "graph_then_evidence", "graph_assist"
    );

    private final SuperAgentKnowledgeScopeNodeMapper scopeNodeMapper;
    private final SuperAgentKnowledgeTopicNodeMapper topicNodeMapper;
    private final SuperAgentTopicDocumentRelationMapper topicDocumentRelationMapper;
    private final SuperAgentKnowledgeRouteTraceMapper knowledgeRouteTraceMapper;
    private final SuperAgentDocumentMapper documentMapper;
    private final DocumentProfileService documentProfileService;
    private final UidGenerator uidGenerator;

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeScopeItemVo saveScope(KnowledgeScopeSaveDto dto) {
        validateScope(dto);
        String scopeCode = normalizeCode(dto.getScopeCode(), "scopeCode");
        Long scopeId = StrUtil.isBlank(dto.getId()) ? null : parseRequiredLong(dto.getId(), "id");
        SuperAgentKnowledgeScopeNode entity;
        boolean creating;
        if (scopeId == null) {
            entity = findScopeByCode(scopeCode);
            creating = entity == null;
            if (entity != null && Objects.equals(entity.getStatus(), BusinessStatus.YES.getCode())) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "scopeCode 已存在。");
            }
        }
        else {
            entity = requireActiveScopeById(scopeId);
            creating = false;
            if (!Objects.equals(entity.getScopeCode(), scopeCode)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "scopeCode 创建后不可修改。");
            }
        }
        ensureScopeNameAvailable(dto.getScopeName(), scopeId);
        ensureParentScopeValid(dto.getParentScopeCode(), scopeCode);
        if (creating) {
            entity = new SuperAgentKnowledgeScopeNode();
            entity.setId(uidGenerator.getUid());
            entity.setScopeCode(scopeCode);
        }
        String oldScopeName = safeText(entity.getScopeName());
        entity.setStatus(BusinessStatus.YES.getCode());
        entity.setScopeName(dto.getScopeName().trim());
        entity.setParentScopeCode(normalizeOptionalCode(dto.getParentScopeCode(), "parentScopeCode"));
        entity.setDescription(blankToEmpty(dto.getDescription()));
        entity.setAliases(blankToEmpty(dto.getAliases()));
        entity.setExamples(blankToEmpty(dto.getExamples()));
        entity.setSortOrder(parseSortOrder(dto.getSortOrder()));
        if (creating) {
            scopeNodeMapper.insert(entity);
        }
        else {
            scopeNodeMapper.updateById(entity);
            scopeNodeMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeScopeNode>()
                .eq(SuperAgentKnowledgeScopeNode::getId, entity.getId())
                .set(SuperAgentKnowledgeScopeNode::getParentScopeCode, entity.getParentScopeCode()));
        }
        if (!Objects.equals(oldScopeName, entity.getScopeName())) {
            documentMapper.update(null, new LambdaUpdateWrapper<SuperAgentDocument>()
                .eq(SuperAgentDocument::getKnowledgeScopeCode, entity.getScopeCode())
                .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode())
                .set(SuperAgentDocument::getKnowledgeScopeName, entity.getScopeName()));
        }
        return toScopeVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteScope(KnowledgeScopeDeleteDto dto) {
        SuperAgentKnowledgeScopeNode scope = requireActiveScope(dto == null ? null : dto.getId(),
            dto == null ? null : dto.getScopeCode());
        long topicCount = topicNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getScopeCode, scope.getScopeCode())
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode()));
        if (topicCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该知识范围下仍有主题，请先删除或迁移主题。");
        }
        long documentCount = documentMapper.selectCount(new LambdaQueryWrapper<SuperAgentDocument>()
            .eq(SuperAgentDocument::getKnowledgeScopeCode, scope.getScopeCode())
            .eq(SuperAgentDocument::getStatus, BusinessStatus.YES.getCode()));
        if (documentCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该知识范围仍被文档使用，请先调整文档归属。");
        }
        long childScopeCount = scopeNodeMapper.selectCount(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getParentScopeCode, scope.getScopeCode())
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode()));
        if (childScopeCount > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该知识范围下仍有子范围，请先删除或迁移子范围。");
        }
        return scopeNodeMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getId, scope.getId())
            .set(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public List<KnowledgeScopeItemVo> listScopes() {
        return scopeNodeMapper.selectList(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
                .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode())
                .orderByAsc(SuperAgentKnowledgeScopeNode::getSortOrder, SuperAgentKnowledgeScopeNode::getId))
            .stream()
            .map(this::toScopeVo)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public KnowledgeTopicItemVo saveTopic(KnowledgeTopicSaveDto dto) {
        validateTopic(dto);
        String topicCode = normalizeCode(dto.getTopicCode(), "topicCode");
        String scopeCode = normalizeCode(dto.getScopeCode(), "scopeCode");
        SuperAgentKnowledgeScopeNode scope = requireActiveScopeByCode(scopeCode);
        Long topicId = StrUtil.isBlank(dto.getId()) ? null : parseRequiredLong(dto.getId(), "id");
        SuperAgentKnowledgeTopicNode entity;
        boolean creating;
        if (topicId == null) {
            entity = findTopicByCode(topicCode);
            creating = entity == null;
            if (entity != null && Objects.equals(entity.getStatus(), BusinessStatus.YES.getCode())) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "topicCode 已存在。");
            }
        }
        else {
            entity = requireActiveTopicById(topicId);
            creating = false;
            if (!Objects.equals(entity.getTopicCode(), topicCode)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "topicCode 创建后不可修改。");
            }
            if (!Objects.equals(entity.getScopeCode(), scopeCode) && hasActiveTopicDocumentRelations(topicCode)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "该主题已关联文档，请先解除关联后再调整所属知识范围。");
            }
        }
        ensureTopicNameAvailable(scopeCode, dto.getTopicName(), topicId);
        String answerShape = normalizeTopicOption(dto.getAnswerShape(), ANSWER_SHAPES, "answerShape");
        String executionPreference = normalizeTopicOption(
            dto.getExecutionPreference(), EXECUTION_PREFERENCES, "executionPreference");
        if (creating) {
            entity = new SuperAgentKnowledgeTopicNode();
            entity.setId(uidGenerator.getUid());
            entity.setTopicCode(topicCode);
        }
        entity.setStatus(BusinessStatus.YES.getCode());
        entity.setTopicName(dto.getTopicName().trim());
        entity.setScopeCode(scope.getScopeCode());
        entity.setDescription(blankToEmpty(dto.getDescription()));
        entity.setAliases(blankToEmpty(dto.getAliases()));
        entity.setExamples(blankToEmpty(dto.getExamples()));
        entity.setAnswerShape(answerShape);
        entity.setExecutionPreference(executionPreference);
        entity.setSortOrder(parseSortOrder(dto.getSortOrder()));
        if (creating) {
            topicNodeMapper.insert(entity);
        }
        else {
            topicNodeMapper.updateById(entity);
        }
        return toTopicVo(entity);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean deleteTopic(KnowledgeTopicDeleteDto dto) {
        SuperAgentKnowledgeTopicNode topic = requireActiveTopic(dto == null ? null : dto.getId(),
            dto == null ? null : dto.getTopicCode());
        if (hasActiveTopicDocumentRelations(topic.getTopicCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该主题仍有关联文档，请先移除主题文档关联。");
        }
        return topicNodeMapper.update(null, new LambdaUpdateWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getId, topic.getId())
            .set(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.NO.getCode())) > 0;
    }

    @Override
    public List<KnowledgeTopicItemVo> listTopics(KnowledgeTopicQueryDto dto) {
        String scopeCode = dto == null ? "" : safeText(dto.getScopeCode());
        LambdaQueryWrapper<SuperAgentKnowledgeTopicNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode())
            .orderByAsc(SuperAgentKnowledgeTopicNode::getSortOrder, SuperAgentKnowledgeTopicNode::getId);
        if (scopeCode != null && !scopeCode.isBlank()) {
            wrapper.eq(SuperAgentKnowledgeTopicNode::getScopeCode, scopeCode);
        }
        return topicNodeMapper.selectList(wrapper).stream().map(this::toTopicVo).toList();
    }

    @Override
    public DocumentProfileVo queryProfile(DocumentProfileDetailQueryDto dto) {
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "documentId");
        SuperAgentDocumentProfile profile = documentProfileService.getByDocumentId(documentId)
            .orElseThrow(() -> new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档画像不存在。"));
        return toProfileVo(profile);
    }

    @Override
    public DocumentProfileVo regenerateProfile(DocumentProfileRegenerateDto dto) {
        Long documentId = parseRequiredLong(dto == null ? null : dto.getDocumentId(), "documentId");
        return toProfileVo(documentProfileService.regenerateProfile(documentId));
    }

    @Override
    public List<DocumentProfileVo> batchRegenerateProfiles(DocumentProfileBatchRegenerateDto dto) {
        List<Long> documentIds = dto == null || dto.getDocumentIds() == null
            ? List.of()
            : dto.getDocumentIds().stream().map(value -> parseRequiredLong(value, "documentId")).toList();
        return documentProfileService.batchRegenerateProfiles(documentIds).stream()
            .map(this::toProfileVo)
            .toList();
    }

    @Override
    public List<TopicDocumentRelationItemVo> listTopicDocuments(TopicDocumentRelationListQueryDto dto) {
        String topicCode = dto == null ? "" : safeText(dto.getTopicCode());
        LambdaQueryWrapper<SuperAgentTopicDocumentRelation> wrapper = new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())
            .orderByDesc(SuperAgentTopicDocumentRelation::getRelationScore, SuperAgentTopicDocumentRelation::getId);
        if (topicCode != null && !topicCode.isBlank()) {
            wrapper.eq(SuperAgentTopicDocumentRelation::getTopicCode, topicCode);
        }
        return topicDocumentRelationMapper.selectList(wrapper).stream()
            .map(this::toRelationVo)
            .toList();
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public TopicDocumentRelationItemVo saveTopicDocumentRelation(TopicDocumentRelationSaveDto dto) {
        if (dto == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请求参数不能为空。");
        }
        String topicCode = normalizeCode(dto.getTopicCode(), "topicCode");
        Long documentId = parseRequiredLong(dto.getDocumentId(), "documentId");
        SuperAgentKnowledgeTopicNode topic = requireActiveTopicByCode(topicCode);
        SuperAgentKnowledgeScopeNode scope = requireActiveScopeByCode(topic.getScopeCode());
        SuperAgentDocument document = documentMapper.selectById(documentId);
        if (document == null || !Objects.equals(document.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "文档不存在或已删除。");
        }
        String documentScopeCode = safeText(document.getKnowledgeScopeCode());
        String documentScopeName = safeText(document.getKnowledgeScopeName());
        if (!documentScopeCode.isBlank() && !Objects.equals(documentScopeCode, scope.getScopeCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该文档所属知识范围与主题不一致，请先调整文档归属。");
        }
        if (documentScopeCode.isBlank() && !documentScopeName.isBlank()
            && !Objects.equals(documentScopeName, scope.getScopeName())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "该文档所属知识范围名称与主题不一致，请先调整文档归属。");
        }
        boolean documentChanged = false;
        if (documentScopeCode.isBlank()) {
            document.setKnowledgeScopeCode(scope.getScopeCode());
            documentChanged = true;
        }
        if (!Objects.equals(documentScopeName, scope.getScopeName())) {
            document.setKnowledgeScopeName(scope.getScopeName());
            documentChanged = true;
        }
        if (documentChanged) {
            documentMapper.updateById(document);
        }
        SuperAgentTopicDocumentRelation relation = topicDocumentRelationMapper.selectOne(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getTopicCode, topicCode)
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId)
            .last("LIMIT 1"));
        if (relation == null) {
            relation = new SuperAgentTopicDocumentRelation();
            relation.setId(uidGenerator.getUid());
            relation.setTopicCode(topicCode);
            relation.setDocumentId(documentId);
        }
        relation.setStatus(BusinessStatus.YES.getCode());
        relation.setRelationScore(parseRelationScore(dto.getRelationScore()));
        relation.setRelationSource("manual");
        relation.setReason(blankToEmpty(dto.getReason()));
        if (relation.getCreateTime() == null) {
            topicDocumentRelationMapper.insert(relation);
        }
        else {
            topicDocumentRelationMapper.updateById(relation);
        }
        return toRelationVo(relation);
    }

    @Override
    @Transactional(rollbackFor = Exception.class)
    public boolean removeTopicDocumentRelation(TopicDocumentRelationRemoveDto dto) {
        if (dto == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请求参数不能为空。");
        }
        String topicCode = normalizeCode(dto.getTopicCode(), "topicCode");
        Long documentId = parseRequiredLong(dto.getDocumentId(), "documentId");
        int updated = topicDocumentRelationMapper.update(null, new LambdaUpdateWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getTopicCode, topicCode)
            .eq(SuperAgentTopicDocumentRelation::getDocumentId, documentId)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())
            .set(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.NO.getCode()));
        if (updated == 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "主题文档关联不存在或已移除。");
        }
        return true;
    }

    @Override
    public KnowledgeRouteTracePageVo queryRouteTracePage(KnowledgeRouteTraceQueryDto dto) {
        int pageNo = parseInteger(dto == null ? null : dto.getPageNo(), 1);
        int pageSize = parseInteger(dto == null ? null : dto.getPageSize(), 20);
        String conversationId = dto == null ? "" : safeText(dto.getConversationId());
        String mode = dto == null ? "" : safeText(dto.getMode());
        String routeStatus = dto == null ? "" : safeText(dto.getRouteStatus());
        LambdaQueryWrapper<org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace> wrapper =
            new LambdaQueryWrapper<org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace>()
                .eq(org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getStatus, BusinessStatus.YES.getCode())
                .orderByDesc(org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getCreateTime,
                    org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getId);
        if (StrUtil.isNotBlank(conversationId)) {
            wrapper.eq(org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getConversationId, conversationId);
        }
        if (StrUtil.isNotBlank(mode)) {
            wrapper.eq(org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getMode, mode);
        }
        if (StrUtil.isNotBlank(routeStatus)) {
            Integer parsedStatus = parseInteger(routeStatus, -1);
            if (parsedStatus > 0) {
                wrapper.eq(org.javaup.ai.manage.data.SuperAgentKnowledgeRouteTrace::getRouteStatus, parsedStatus);
            }
        }
        long total = knowledgeRouteTraceMapper.selectCount(wrapper);
        List<KnowledgeRouteTraceItemVo> records = knowledgeRouteTraceMapper.selectList(wrapper.last("LIMIT " + ((long) (pageNo - 1) * pageSize) + "," + pageSize))
            .stream()
            .map(item -> new KnowledgeRouteTraceItemVo(
                String.valueOf(item.getId()),
                safeText(item.getConversationId()),
                item.getExchangeId() == null ? "" : String.valueOf(item.getExchangeId()),
                safeText(item.getQuestion()),
                safeText(item.getRewriteQuestion()),
                safeText(item.getMode()),
                safeText(item.getTopScopesJson()),
                safeText(item.getTopTopicsJson()),
                safeText(item.getTopDocumentsJson()),
                item.getSelectedDocumentId() == null ? "" : String.valueOf(item.getSelectedDocumentId()),
                item.getHitSelectedDocument() == null ? "" : String.valueOf(item.getHitSelectedDocument()),
                item.getConfidence() == null ? "0.0000" : item.getConfidence().toPlainString(),
                item.getRouteStatus() == null ? "" : String.valueOf(item.getRouteStatus()),
                safeText(item.getErrorMsg()),
                item.getCreateTime() == null ? "" : String.valueOf(item.getCreateTime().getTime())
            ))
            .toList();
        long totalPages = total <= 0 ? 0 : (total + pageSize - 1) / pageSize;
        return new KnowledgeRouteTracePageVo(
            String.valueOf(pageNo),
            String.valueOf(pageSize),
            String.valueOf(total),
            String.valueOf(totalPages),
            records
        );
    }

    private void validateScope(KnowledgeScopeSaveDto dto) {
        if (dto == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请求参数不能为空。");
        }
        normalizeCode(dto.getScopeCode(), "scopeCode");
        validateRequiredText(dto.getScopeName(), "scopeName", MAX_NAME_LENGTH);
        normalizeOptionalCode(dto.getParentScopeCode(), "parentScopeCode");
        validateOptionalText(dto.getDescription(), "description", MAX_DESCRIPTION_LENGTH);
        validateOptionalText(dto.getAliases(), "aliases", MAX_ALIASES_LENGTH);
        validateOptionalText(dto.getExamples(), "examples", MAX_EXAMPLES_LENGTH);
    }

    private void validateTopic(KnowledgeTopicSaveDto dto) {
        if (dto == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "请求参数不能为空。");
        }
        normalizeCode(dto.getTopicCode(), "topicCode");
        normalizeCode(dto.getScopeCode(), "scopeCode");
        validateRequiredText(dto.getTopicName(), "topicName", MAX_NAME_LENGTH);
        validateOptionalText(dto.getDescription(), "description", MAX_DESCRIPTION_LENGTH);
        validateOptionalText(dto.getAliases(), "aliases", MAX_ALIASES_LENGTH);
        validateOptionalText(dto.getExamples(), "examples", MAX_EXAMPLES_LENGTH);
    }

    private String normalizeCode(String rawValue, String fieldName) {
        String code = safeText(rawValue);
        if (code.isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        if (!CODE_PATTERN.matcher(code).matches()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                fieldName + "必须以字母开头，仅支持字母、数字、下划线和中划线，长度不超过 64。");
        }
        return code;
    }

    private String normalizeOptionalCode(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            return null;
        }
        return normalizeCode(rawValue, fieldName);
    }

    private void validateRequiredText(String rawValue, String fieldName, int maxLength) {
        String value = safeText(rawValue);
        if (value.isBlank()) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        validateOptionalText(value, fieldName, maxLength);
    }

    private void validateOptionalText(String rawValue, String fieldName, int maxLength) {
        if (rawValue != null && rawValue.trim().length() > maxLength) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                fieldName + "长度不能超过 " + maxLength + " 个字符。");
        }
    }

    private String normalizeTopicOption(String rawValue, Set<String> allowedValues, String fieldName) {
        String value = safeText(rawValue);
        if (value.isBlank()) {
            return "";
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (!allowedValues.contains(normalized)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + " 值不合法。");
        }
        return normalized;
    }

    private SuperAgentKnowledgeScopeNode findScopeByCode(String scopeCode) {
        return scopeNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getScopeCode, scopeCode)
            .last("LIMIT 1"));
    }

    private SuperAgentKnowledgeScopeNode requireActiveScopeById(Long scopeId) {
        SuperAgentKnowledgeScopeNode scope = scopeId == null ? null : scopeNodeMapper.selectById(scopeId);
        if (scope == null || !Objects.equals(scope.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识范围不存在或已删除。");
        }
        return scope;
    }

    private SuperAgentKnowledgeScopeNode requireActiveScopeByCode(String scopeCode) {
        SuperAgentKnowledgeScopeNode scope = findScopeByCode(scopeCode);
        if (scope == null || !Objects.equals(scope.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识范围不存在或已删除。");
        }
        return scope;
    }

    private SuperAgentKnowledgeScopeNode requireActiveScope(String rawId, String rawCode) {
        Long scopeId = StrUtil.isBlank(rawId) ? null : parseRequiredLong(rawId, "id");
        String scopeCode = normalizeOptionalCode(rawCode, "scopeCode");
        if (scopeId == null && scopeCode == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 和 scopeCode 至少提供一个。");
        }
        SuperAgentKnowledgeScopeNode scopeById = scopeId == null ? null : requireActiveScopeById(scopeId);
        SuperAgentKnowledgeScopeNode scopeByCode = scopeCode == null ? null : requireActiveScopeByCode(scopeCode);
        if (scopeById != null && scopeByCode != null
            && !Objects.equals(scopeById.getId(), scopeByCode.getId())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 与 scopeCode 不匹配。");
        }
        return scopeById == null ? scopeByCode : scopeById;
    }

    private void ensureScopeNameAvailable(String scopeName, Long excludeScopeId) {
        LambdaQueryWrapper<SuperAgentKnowledgeScopeNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeScopeNode>()
            .eq(SuperAgentKnowledgeScopeNode::getScopeName, safeText(scopeName))
            .eq(SuperAgentKnowledgeScopeNode::getStatus, BusinessStatus.YES.getCode());
        if (excludeScopeId != null) {
            wrapper.ne(SuperAgentKnowledgeScopeNode::getId, excludeScopeId);
        }
        if (scopeNodeMapper.selectCount(wrapper) > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识范围名称已存在。");
        }
    }

    private void ensureParentScopeValid(String parentScopeCode, String scopeCode) {
        String currentCode = normalizeOptionalCode(parentScopeCode, "parentScopeCode");
        Set<String> visited = new HashSet<>();
        int depth = 0;
        while (currentCode != null) {
            if (depth >= MAX_PARENT_SCOPE_DEPTH) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "父范围层级过深，最多支持 " + MAX_PARENT_SCOPE_DEPTH + " 层。");
            }
            if (Objects.equals(currentCode, scopeCode) || !visited.add(currentCode)) {
                throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                    "父范围不能指向自身或形成循环引用。");
            }
            SuperAgentKnowledgeScopeNode parent = requireActiveScopeByCode(currentCode);
            currentCode = normalizeOptionalCode(parent.getParentScopeCode(), "parentScopeCode");
            depth++;
        }
    }

    private SuperAgentKnowledgeTopicNode findTopicByCode(String topicCode) {
        return topicNodeMapper.selectOne(new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getTopicCode, topicCode)
            .last("LIMIT 1"));
    }

    private SuperAgentKnowledgeTopicNode requireActiveTopicById(Long topicId) {
        SuperAgentKnowledgeTopicNode topic = topicId == null ? null : topicNodeMapper.selectById(topicId);
        if (topic == null || !Objects.equals(topic.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识主题不存在或已删除。");
        }
        return topic;
    }

    private SuperAgentKnowledgeTopicNode requireActiveTopicByCode(String topicCode) {
        SuperAgentKnowledgeTopicNode topic = findTopicByCode(topicCode);
        if (topic == null || !Objects.equals(topic.getStatus(), BusinessStatus.YES.getCode())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "知识主题不存在或已删除。");
        }
        return topic;
    }

    private SuperAgentKnowledgeTopicNode requireActiveTopic(String rawId, String rawCode) {
        Long topicId = StrUtil.isBlank(rawId) ? null : parseRequiredLong(rawId, "id");
        String topicCode = normalizeOptionalCode(rawCode, "topicCode");
        if (topicId == null && topicCode == null) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 和 topicCode 至少提供一个。");
        }
        SuperAgentKnowledgeTopicNode topicById = topicId == null ? null : requireActiveTopicById(topicId);
        SuperAgentKnowledgeTopicNode topicByCode = topicCode == null ? null : requireActiveTopicByCode(topicCode);
        if (topicById != null && topicByCode != null
            && !Objects.equals(topicById.getId(), topicByCode.getId())) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "id 与 topicCode 不匹配。");
        }
        return topicById == null ? topicByCode : topicById;
    }

    private void ensureTopicNameAvailable(String scopeCode, String topicName, Long excludeTopicId) {
        LambdaQueryWrapper<SuperAgentKnowledgeTopicNode> wrapper = new LambdaQueryWrapper<SuperAgentKnowledgeTopicNode>()
            .eq(SuperAgentKnowledgeTopicNode::getScopeCode, scopeCode)
            .eq(SuperAgentKnowledgeTopicNode::getTopicName, safeText(topicName))
            .eq(SuperAgentKnowledgeTopicNode::getStatus, BusinessStatus.YES.getCode());
        if (excludeTopicId != null) {
            wrapper.ne(SuperAgentKnowledgeTopicNode::getId, excludeTopicId);
        }
        if (topicNodeMapper.selectCount(wrapper) > 0) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "同一知识范围下主题名称已存在。");
        }
    }

    private boolean hasActiveTopicDocumentRelations(String topicCode) {
        return topicDocumentRelationMapper.selectCount(new LambdaQueryWrapper<SuperAgentTopicDocumentRelation>()
            .eq(SuperAgentTopicDocumentRelation::getTopicCode, topicCode)
            .eq(SuperAgentTopicDocumentRelation::getStatus, BusinessStatus.YES.getCode())) > 0;
    }

    private BigDecimal parseRelationScore(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return new BigDecimal("0.9000");
        }
        try {
            BigDecimal score = new BigDecimal(rawValue.trim());
            if (score.compareTo(BigDecimal.ZERO) < 0 || score.compareTo(BigDecimal.ONE) > 0) {
                throw new NumberFormatException("out of range");
            }
            return score.setScale(4, RoundingMode.HALF_UP);
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(),
                "relationScore 必须是 0 到 1 之间的数字。");
        }
    }

    private Integer parseSortOrder(String rawValue) {
        if (StrUtil.isBlank(rawValue)) {
            return 0;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), "sortOrder 必须是整数。");
        }
    }

    private String blankToEmpty(String text) {
        return safeText(text);
    }

    private KnowledgeScopeItemVo toScopeVo(SuperAgentKnowledgeScopeNode node) {
        return new KnowledgeScopeItemVo(
            String.valueOf(node.getId()),
            safeText(node.getScopeCode()),
            safeText(node.getScopeName()),
            safeText(node.getParentScopeCode()),
            safeText(node.getDescription()),
            safeText(node.getAliases()),
            safeText(node.getExamples()),
            String.valueOf(Optional.ofNullable(node.getSortOrder()).orElse(0))
        );
    }

    private KnowledgeTopicItemVo toTopicVo(SuperAgentKnowledgeTopicNode node) {
        return new KnowledgeTopicItemVo(
            String.valueOf(node.getId()),
            safeText(node.getTopicCode()),
            safeText(node.getTopicName()),
            safeText(node.getScopeCode()),
            safeText(node.getDescription()),
            safeText(node.getAliases()),
            safeText(node.getExamples()),
            safeText(node.getAnswerShape()),
            safeText(node.getExecutionPreference()),
            String.valueOf(Optional.ofNullable(node.getSortOrder()).orElse(0))
        );
    }

    private DocumentProfileVo toProfileVo(SuperAgentDocumentProfile profile) {
        return new DocumentProfileVo(
            String.valueOf(profile.getDocumentId()),
            safeText(profile.getDocumentSummary()),
            safeText(profile.getDocumentType()),
            safeText(profile.getCoreTopics()),
            safeText(profile.getExampleQuestions()),
            String.valueOf(Optional.ofNullable(profile.getGraphFriendly()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsGraphOutline()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsItemLookup()).orElse(0)),
            String.valueOf(Optional.ofNullable(profile.getSupportsGraphAssist()).orElse(0)),
            safeText(profile.getProfileSource()),
            String.valueOf(Optional.ofNullable(profile.getProfileStatus()).orElse(0)),
            safeText(profile.getErrorMsg())
        );
    }

    private TopicDocumentRelationItemVo toRelationVo(SuperAgentTopicDocumentRelation relation) {
        SuperAgentDocument document = documentMapper.selectById(relation.getDocumentId());
        return new TopicDocumentRelationItemVo(
            safeText(relation.getTopicCode()),
            String.valueOf(relation.getDocumentId()),
            document == null ? "" : safeText(document.getDocumentName()),
            document == null ? "" : safeText(document.getKnowledgeScopeCode()),
            document == null ? "" : safeText(document.getKnowledgeScopeName()),
            document == null ? "" : safeText(document.getBusinessCategory()),
            document == null ? "" : safeText(document.getDocumentTags()),
            relation.getRelationScore() == null ? "0.0000" : relation.getRelationScore().toPlainString(),
            safeText(relation.getRelationSource()),
            safeText(relation.getReason())
        );
    }

    private Long parseRequiredLong(String rawValue, String fieldName) {
        if (StrUtil.isBlank(rawValue)) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "不能为空。");
        }
        try {
            Long value = Long.valueOf(rawValue.trim());
            if (value <= 0) {
                throw new NumberFormatException("must be positive");
            }
            return value;
        }
        catch (NumberFormatException exception) {
            throw new SuperAgentFrameException(BaseCode.PARAMETER_ERROR.getCode(), fieldName + "格式非法。");
        }
    }

    private Integer parseInteger(String rawValue, Integer fallback) {
        if (StrUtil.isBlank(rawValue)) {
            return fallback;
        }
        try {
            return Integer.valueOf(rawValue.trim());
        }
        catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String safeText(String text) {
        return text == null ? "" : text.trim();
    }
}
