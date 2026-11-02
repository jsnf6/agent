package org.javaup.ai.manage.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DocumentTypeClassifierTest {

    @Test
    void shouldPreferTitleOverIncidentalFaultMentions() {
        assertEquals(DocumentTypeClassifier.MANUAL, DocumentTypeClassifier.classify(
            "澄星智能新员工入职培训手册.md",
            "澄星智能新员工入职培训手册.md",
            List.of("编制目的与阅读说明", "入职前准备清单"),
            "若账号无法登录，请联系 IT 服务台处理；系统故障时按应急预案处理。",
            true
        ));
        assertEquals(DocumentTypeClassifier.RULE, DocumentTypeClassifier.classify(
            "差旅与费用报销管理办法.md",
            "差旅与费用报销管理办法.md",
            List.of("制定目的与基本原则", "差旅类型与适用场景"),
            "客户现场重大故障处理属于紧急支援出差场景。",
            true
        ));
        assertEquals(DocumentTypeClassifier.MANUAL, DocumentTypeClassifier.classify(
            "XX-200智能网关产品手册.pdf",
            "XX-200智能网关产品手册.pdf",
            List.of("产品概述", "安装步骤", "常见故障"),
            "设备异常时可参考故障处理章节。",
            true
        ));
    }

    @Test
    void shouldClassifyFaultDocumentFromTitle() {
        assertEquals(DocumentTypeClassifier.TROUBLESHOOTING, DocumentTypeClassifier.classify(
            "核心业务系统故障应急响应预案.md",
            "核心业务系统故障应急响应预案.md",
            List.of("故障等级定义", "故障响应流程"),
            "系统发生异常时启动应急预案。",
            true
        ));
    }

    @Test
    void shouldKeepRuleAndManualTitleSignalsDistinct() {
        assertEquals(DocumentTypeClassifier.RULE, DocumentTypeClassifier.classify(
            "客户数据分级与访问控制管理制度.md",
            "客户数据分级与访问控制管理制度.md",
            List.of("数据分级", "访问控制"),
            "生产故障不得绕过审批流程。",
            false
        ));
        assertEquals(DocumentTypeClassifier.MANUAL, DocumentTypeClassifier.classify(
            "生产环境发布与回滚操作规范.md",
            "生产环境发布与回滚操作规范.md",
            List.of("发布步骤", "回滚操作"),
            "发布过程中出现故障时执行回滚。",
            true
        ));
    }

    @Test
    void shouldUseHeadingsAndBodyOnlyWhenTitleHasNoStrongSignal() {
        assertEquals(DocumentTypeClassifier.TROUBLESHOOTING, DocumentTypeClassifier.classify(
            "系统说明.md",
            "系统说明.md",
            List.of("故障现象", "排查步骤", "恢复确认"),
            "故障发生后的处理说明。",
            false
        ));
        assertEquals(DocumentTypeClassifier.TROUBLESHOOTING, DocumentTypeClassifier.classify(
            "系统说明.md",
            "系统说明.md",
            List.of(),
            "故障 故障 排查 排查 异常处理 恢复。",
            false
        ));
        assertEquals(DocumentTypeClassifier.INTRO, DocumentTypeClassifier.classify(
            "系统说明.md",
            "系统说明.md",
            List.of(),
            "系统发生故障时请联系管理员。",
            false
        ));
    }

    @Test
    void shouldRecognizeFaqAndSpecificationTitles() {
        assertEquals(DocumentTypeClassifier.FAQ, DocumentTypeClassifier.classify(
            "平台常见问题.md",
            "平台常见问题.md",
            List.of(),
            "",
            false
        ));
        assertEquals(DocumentTypeClassifier.SPEC, DocumentTypeClassifier.classify(
            "设备技术规格.md",
            "设备技术规格.md",
            List.of(),
            "",
            false
        ));
    }
}
