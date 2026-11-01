package org.javaup.ai.manage.support;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DoclingDocumentStructureMapperTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    private final DoclingDocumentStructureMapper structureMapper = new DoclingDocumentStructureMapper();

    @Test
    void shouldMapDoclingDocumentToStructureTree() throws Exception {
        JsonNode doclingDocument = objectMapper.readTree("""
            {
              "schema_name": "DoclingDocument",
              "body": {
                "self_ref": "#/body",
                "children": [
                  {"$ref": "#/texts/0"},
                  {"$ref": "#/texts/1"},
                  {"$ref": "#/texts/2"},
                  {"$ref": "#/groups/0"},
                  {"$ref": "#/tables/0"},
                  {"$ref": "#/texts/5"},
                  {"$ref": "#/texts/6"}
                ]
              },
              "texts": [
                {"self_ref": "#/texts/0", "label": "title", "text": "产品手册"},
                {"self_ref": "#/texts/1", "label": "section_header", "text": "1. 安装", "level": 1},
                {"self_ref": "#/texts/2", "label": "text", "text": "安装前请确认环境。"},
                {"self_ref": "#/texts/3", "label": "list_item", "text": "准备 JDK 17", "marker": "1."},
                {"self_ref": "#/texts/4", "label": "list_item", "text": "启动 docling-serve", "marker": "2."},
                {"self_ref": "#/texts/5", "label": "section_header", "text": "1.1 验证", "level": 2},
                {"self_ref": "#/texts/6", "label": "text", "text": "访问 /health 检查状态。"},
                {"self_ref": "#/texts/7", "label": "caption", "text": "版本对照表"}
              ],
              "groups": [
                {
                  "self_ref": "#/groups/0",
                  "label": "list",
                  "children": [
                    {"$ref": "#/texts/3"},
                    {"$ref": "#/texts/4"}
                  ]
                }
              ],
              "tables": [
                {
                  "self_ref": "#/tables/0",
                  "label": "table",
                  "captions": [{"$ref": "#/texts/7"}],
                  "data": {
                    "num_rows": 2,
                    "num_cols": 2,
                    "table_cells": [
                      {"start_row_offset_idx": 0, "start_col_offset_idx": 0, "text": "组件"},
                      {"start_row_offset_idx": 0, "start_col_offset_idx": 1, "text": "版本"},
                      {"start_row_offset_idx": 1, "start_col_offset_idx": 0, "text": "Docling"},
                      {"start_row_offset_idx": 1, "start_col_offset_idx": 1, "text": "2.x"}
                    ]
                  }
                }
              ]
            }
            """);

        DoclingDocumentStructureMapper.DoclingStructureMapping mapping = structureMapper.map(
            "产品手册.pdf",
            doclingDocument,
            ""
        );
        List<DocumentStructureNodeCandidate> nodes = mapping.structureNodes();

        assertEquals(6, nodes.size());
        assertEquals("产品手册.pdf", nodes.get(0).getTitle());
        assertEquals("", nodes.get(0).getContentText());
        assertTrue(mapping.markdown().contains("# 产品手册"));
        assertTrue(mapping.markdown().contains("# 1. 安装"));

        DocumentStructureNodeCandidate installSection = findSection(nodes, "安装");
        assertNotNull(installSection);
        assertEquals(DocumentStructureNodeTypeEnum.SECTION.getCode(), installSection.getNodeType());
        assertEquals(1, installSection.getDepth());
        assertEquals("/document/1", installSection.getCanonicalPath());
        assertEquals("1 安装", installSection.getSectionPath());
        assertTrue(installSection.getContentText().contains("1. 安装"));
        assertTrue(installSection.getContentText().contains("安装前请确认环境。"));
        assertTrue(installSection.getContentText().contains("准备 JDK 17"));
        assertTrue(installSection.getContentText().contains("| Docling | 2.x |"));

        DocumentStructureNodeCandidate jdkItem = findListItem(nodes, "准备 JDK 17");
        assertNotNull(jdkItem);
        assertEquals(installSection.getNodeNo(), jdkItem.getParentNodeNo());
        assertEquals("1 安装", jdkItem.getSectionPath());
        assertTrue(jdkItem.getCanonicalPath().startsWith("/document/1/item-"));

        DocumentStructureNodeCandidate doclingItem = findListItem(nodes, "启动 docling-serve");
        assertNotNull(doclingItem);
        assertEquals(jdkItem.getNodeNo(), doclingItem.getPrevSiblingNodeNo());
        assertEquals(doclingItem.getNodeNo(), jdkItem.getNextSiblingNodeNo());

        DocumentStructureNodeCandidate table = findListItem(nodes, "版本对照表");
        assertNotNull(table);
        assertEquals(installSection.getNodeNo(), table.getParentNodeNo());
        assertTrue(table.getContentText().contains("| 组件 | 版本 |"));

        DocumentStructureNodeCandidate verifySection = findSection(nodes, "验证");
        assertNotNull(verifySection);
        assertEquals(installSection.getNodeNo(), verifySection.getParentNodeNo());
        assertEquals(2, verifySection.getDepth());
        assertEquals("1 安装 > 1.1 验证", verifySection.getSectionPath());
        assertTrue(verifySection.getContentText().contains("访问 /health 检查状态。"));
        assertFalse(installSection.getContentText().contains("访问 /health 检查状态。"));
    }

    @Test
    void shouldPreferExportedMarkdownWhenPresent() throws Exception {
        JsonNode doclingDocument = objectMapper.readTree("""
            {
              "body": {"children": [{"$ref": "#/texts/0"}]},
              "texts": [{"self_ref": "#/texts/0", "label": "section_header", "text": "概述", "level": 1}]
            }
            """);

        DoclingDocumentStructureMapper.DoclingStructureMapping mapping = structureMapper.map(
            "说明.pdf",
            doclingDocument,
            "# 导出内容"
        );

        assertEquals("# 导出内容", mapping.markdown());
        assertEquals(1, mapping.structureNodes().stream()
            .filter(node -> DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .count());
    }

    private DocumentStructureNodeCandidate findSection(List<DocumentStructureNodeCandidate> nodes, String title) {
        return nodes.stream()
            .filter(node -> DocumentStructureNodeTypeEnum.SECTION.getCode().equals(node.getNodeType()))
            .filter(node -> title.equals(node.getTitle()))
            .findFirst()
            .orElse(null);
    }

    private DocumentStructureNodeCandidate findListItem(List<DocumentStructureNodeCandidate> nodes, String title) {
        return nodes.stream()
            .filter(node -> DocumentStructureNodeTypeEnum.LIST_ITEM.getCode().equals(node.getNodeType()))
            .filter(node -> title.equals(node.getTitle()))
            .findFirst()
            .orElse(null);
    }
}
