package org.javaup.ai.manage.support;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScopeLexicalScorerTest {

    @Test
    void shouldPreferHrEvidenceForOnboardingManual() {
        String title = "澄星智能新员工入职培训手册";
        List<String> headings = List.of(
            "一 编制目的与阅读说明",
            "三 入职前准备清单",
            "四 入职首周培训路径",
            "五 通识培训课程说明",
            "八 导师机制与 30/60/90 天跟进"
        );
        String summary = "本手册指导新员工完成报到、账号开通、制度学习、岗位融入、试用期目标与转正准备。";

        ScopeLexicalScorer.Evidence hr = ScopeLexicalScorer.score(
            "员工人事管理",
            List.of("HR", "人事", "员工关怀", "入职离职"),
            "涵盖员工考勤、请假、入职离职、薪酬福利及晋升制度等公司人员管理相关规定。",
            title,
            headings,
            summary
        );
        ScopeLexicalScorer.Evidence itSupport = ScopeLexicalScorer.score(
            "IT 支持与报错维修",
            List.of("IT报修", "电脑维修", "网络故障", "账号异常"),
            "涵盖公司内网连接、VPN 配置、办公软件权限申请及硬件设备故障维修指南。",
            title,
            headings,
            summary
        );

        assertTrue(hr.strongEvidence());
        assertFalse(itSupport.strongEvidence());
        assertTrue(hr.score() > itSupport.score());
    }

    @Test
    void shouldNotTreatGenericProcessWordsAsStrongEvidence() {
        ScopeLexicalScorer.Evidence evidence = ScopeLexicalScorer.score(
            "流程规范",
            List.of(),
            "通用流程与制度说明",
            "流程与制度说明",
            List.of("流程说明", "制度概览"),
            "本文件说明通用流程制度。"
        );

        assertFalse(evidence.strongEvidence());
    }
}
