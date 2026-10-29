package org.javaup.ai.manage.support;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import org.javaup.enums.DocumentStructureNodeTypeEnum;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 支撑组件
 * @author: 阿星不是程序员
 **/

@Component
public class DoclingDocumentStructureMapper {

    private static final Pattern DECIMAL_CODE_PATTERN = Pattern.compile("^(\\d+(?:\\.\\d+)*)[.、]?\\s+(.+)$");

    private static final Pattern CHAPTER_CODE_PATTERN = Pattern.compile("^(第[一二三四五六七八九十百千\\d]+[章节条部分篇])\\s*(.+)$");

    private static final Pattern APPENDIX_CODE_PATTERN = Pattern.compile("^(附录\\s*[A-Za-z一二三四五六七八九十百\\d]+)(?:\\s+(.+))?$");

    private static final Pattern CHINESE_OUTLINE_PATTERN = Pattern.compile("^([一二三四五六七八九十百]+)[、.]\\s*(.+)$");

    private static final Pattern STEP_PATTERN = Pattern.compile("^(?:第\\s*[0-9一二三四五六七八九十百]+\\s*步|步骤\\s*[0-9一二三四五六七八九十百]+|step\\s*\\d+)",
        Pattern.CASE_INSENSITIVE);

    private static final Pattern LIST_MARKER_PATTERN = Pattern.compile("^(?:[-*+•]\\s+|\\d+[.)、]\\s+|[（(]\\d+[）)]\\s*|[一二三四五六七八九十]+[、.)]\\s*)");

    private static final Set<String> NOISE_LABELS = Set.of("page_header", "page_footer");

    public DoclingStructureMapping map(String documentTitle,
                                       JsonNode doclingDocument,
                                       String exportedMarkdown) {
        String title = StrUtil.blankToDefault(documentTitle, "文档").trim();
        NodeBuilder builder = new NodeBuilder(title);
        StringBuilder renderedMarkdown = new StringBuilder();
        if (doclingDocument != null && doclingDocument.isObject()) {
            JsonNode root = doclingDocument;
            JsonNode body = doclingDocument.path("body");
            collectChildren(root, body.path("children"), builder, renderedMarkdown, new HashSet<>(), 0);
        }
        String markdown = StrUtil.isNotBlank(exportedMarkdown)
            ? normalizeMarkdown(exportedMarkdown)
            : normalizeMarkdown(renderedMarkdown.toString());
        return new DoclingStructureMapping(markdown, builder.nodes());
    }

    private void collectChildren(JsonNode root,
                                 JsonNode children,
                                 NodeBuilder builder,
                                 StringBuilder renderedMarkdown,
                                 Set<String> visited,
                                 int listDepth) {
        if (children == null || !children.isArray()) {
            return;
        }
        for (JsonNode child : children) {
            String reference = referenceOf(child);
            JsonNode item = resolve(root, child);
            if (item == null || item.isMissingNode() || item.isNull()) {
                continue;
            }
            if (StrUtil.isNotBlank(reference) && !visited.add(reference)) {
                continue;
            }
            String category = categoryOf(reference, item);
            if (isGroup(item, category)) {
                String label = text(item, "label").toLowerCase(Locale.ROOT);
                int nextListDepth = isListGroup(label) ? listDepth + 1 : listDepth;
                collectChildren(root, item.path("children"), builder, renderedMarkdown, visited, nextListDepth);
                continue;
            }
            processItem(root, item, category, builder, renderedMarkdown, listDepth);
        }
    }

    private void processItem(JsonNode root,
                             JsonNode item,
                             String category,
                             NodeBuilder builder,
                             StringBuilder renderedMarkdown,
                             int listDepth) {
        if ("tables".equals(category) || item.has("data")) {
            String markdown = tableToMarkdown(root, item);
            String caption = itemCaption(root, item);
            builder.appendTable(caption, markdown, renderedMarkdown);
            return;
        }
        if ("pictures".equals(category) || item.has("image")) {
            String caption = itemCaption(root, item);
            builder.appendPicture(caption, renderedMarkdown);
            return;
        }
        if (!"texts".equals(category) && !item.has("text") && !item.has("orig")) {
            return;
        }

        String label = text(item, "label").toLowerCase(Locale.ROOT);
        if (NOISE_LABELS.contains(label)) {
            return;
        }
        String value = firstNonBlank(text(item, "text"), text(item, "orig"));
        if (StrUtil.isBlank(value)) {
            return;
        }
        switch (label) {
            case "title" -> builder.appendTitle(value, renderedMarkdown);
            case "section_header" -> builder.appendSection(value, item.path("level").asInt(1), renderedMarkdown);
            case "list_item" -> builder.appendListItem(value, text(item, "marker"), listDepth, renderedMarkdown);
            case "code" -> builder.appendBody(value, label, renderedMarkdown);
            case "formula" -> builder.appendBody(value, label, renderedMarkdown);
            case "caption", "footnote", "reference", "text", "paragraph" -> builder.appendBody(value, label, renderedMarkdown);
            default -> builder.appendBody(value, label, renderedMarkdown);
        }
    }

    private boolean isGroup(JsonNode item, String category) {
        if ("groups".equals(category)) {
            return true;
        }
        if ("tables".equals(category) || "pictures".equals(category) || item.has("data") || item.has("image")) {
            return false;
        }
        return item.path("children").isArray()
            && !item.has("text")
            && !item.has("orig")
            && !"texts".equals(category);
    }

    private boolean isListGroup(String label) {
        return "list".equals(label) || "ordered_list".equals(label) || "unordered_list".equals(label);
    }

    private JsonNode resolve(JsonNode root, JsonNode child) {
        if (child == null || child.isNull()) {
            return null;
        }
        String reference = referenceOf(child);
        if (StrUtil.isBlank(reference)) {
            return child;
        }
        if (!reference.startsWith("#")) {
            return null;
        }
        return root.at(reference.substring(1));
    }

    private String referenceOf(JsonNode node) {
        if (node == null || node.isNull()) {
            return "";
        }
        if (node.isTextual()) {
            return node.asText("");
        }
        if (node.hasNonNull("$ref")) {
            return node.get("$ref").asText("");
        }
        if (node.hasNonNull("self_ref")) {
            return node.get("self_ref").asText("");
        }
        return "";
    }

    private String categoryOf(String reference, JsonNode item) {
        if (StrUtil.isNotBlank(reference) && reference.startsWith("#/")) {
            String[] segments = reference.substring(2).split("/", 2);
            if (segments.length > 0 && StrUtil.isNotBlank(segments[0])) {
                return segments[0];
            }
        }
        if (item.has("data")) {
            return "tables";
        }
        if (item.has("image")) {
            return "pictures";
        }
        if (item.has("text") || item.has("orig")) {
            return "texts";
        }
        return "groups";
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.hasNonNull(fieldName)) {
            return "";
        }
        return node.get(fieldName).asText("");
    }

    private String firstNonBlank(String first, String second) {
        return StrUtil.isNotBlank(first) ? first : StrUtil.blankToDefault(second, "");
    }

    private String normalizeMarkdown(String markdown) {
        if (markdown == null) {
            return "";
        }
        return markdown
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .replace('\u0000', ' ')
            .replaceAll("\\n{3,}", "\n\n")
            .trim();
    }

    private String tableToMarkdown(JsonNode root, JsonNode table) {
        JsonNode data = table.path("data");
        int rows = data.path("num_rows").asInt(0);
        int cols = data.path("num_cols").asInt(0);
        if (rows <= 0 || cols <= 0) {
            return "";
        }
        String[][] matrix = new String[rows][cols];
        JsonNode cells = data.path("table_cells");
        if (cells.isArray()) {
            for (JsonNode cell : cells) {
                int row = firstNonNegativeInt(cell, "row", "row_index", "start_row_offset_idx");
                int col = firstNonNegativeInt(cell, "col", "col_index", "start_col_offset_idx");
                if (row < 0 || row >= rows || col < 0 || col >= cols) {
                    continue;
                }
                String cellText = firstNonBlank(text(cell, "text"), text(cell, "orig"));
                if (StrUtil.isBlank(cellText)) {
                    cellText = childText(root, cell);
                }
                matrix[row][col] = cleanCell(cellText);
            }
        }
        boolean hasContent = false;
        for (String[] row : matrix) {
            for (String cell : row) {
                if (StrUtil.isNotBlank(cell)) {
                    hasContent = true;
                    break;
                }
            }
            if (hasContent) {
                break;
            }
        }
        if (!hasContent) {
            return "";
        }

        StringBuilder markdown = new StringBuilder();
        markdown.append("| ");
        for (int col = 0; col < cols; col++) {
            markdown.append(StrUtil.blankToDefault(matrix[0][col], "")).append(" | ");
        }
        markdown.append('\n').append('|');
        for (int col = 0; col < cols; col++) {
            markdown.append(" --- |");
        }
        markdown.append('\n');
        for (int row = 1; row < rows; row++) {
            markdown.append("| ");
            for (int col = 0; col < cols; col++) {
                markdown.append(StrUtil.blankToDefault(matrix[row][col], "")).append(" | ");
            }
            markdown.append('\n');
        }
        return markdown.toString().trim();
    }

    private int firstNonNegativeInt(JsonNode node, String... fieldNames) {
        for (String fieldName : fieldNames) {
            if (node.hasNonNull(fieldName) && node.get(fieldName).isNumber()) {
                int value = node.get(fieldName).asInt(-1);
                if (value >= 0) {
                    return value;
                }
            }
        }
        return -1;
    }

    private String itemCaption(JsonNode root, JsonNode item) {
        String caption = captionFromRefs(root, item.path("captions"));
        if (StrUtil.isNotBlank(caption)) {
            return caption;
        }
        JsonNode children = item.path("children");
        if (!children.isArray()) {
            return "";
        }
        for (JsonNode child : children) {
            JsonNode resolved = resolve(root, child);
            if (resolved == null || resolved.isMissingNode()) {
                continue;
            }
            String label = text(resolved, "label").toLowerCase(Locale.ROOT);
            if ("caption".equals(label)) {
                String value = firstNonBlank(text(resolved, "text"), text(resolved, "orig"));
                if (StrUtil.isNotBlank(value)) {
                    return value.trim();
                }
            }
        }
        return "";
    }

    private String captionFromRefs(JsonNode root, JsonNode references) {
        if (references == null || !references.isArray()) {
            return "";
        }
        for (JsonNode reference : references) {
            JsonNode resolved = resolve(root, reference);
            if (resolved == null || resolved.isMissingNode()) {
                continue;
            }
            String value = firstNonBlank(text(resolved, "text"), text(resolved, "orig"));
            if (StrUtil.isNotBlank(value)) {
                return value.trim();
            }
        }
        return "";
    }

    private String childText(JsonNode root, JsonNode item) {
        JsonNode children = item.path("children");
        if (!children.isArray()) {
            return "";
        }
        StringBuilder value = new StringBuilder();
        for (JsonNode child : children) {
            JsonNode resolved = resolve(root, child);
            if (resolved == null || resolved.isMissingNode()) {
                continue;
            }
            String text = firstNonBlank(text(resolved, "text"), text(resolved, "orig"));
            if (StrUtil.isBlank(text)) {
                continue;
            }
            if (!value.isEmpty()) {
                value.append(' ');
            }
            value.append(text.trim());
        }
        return value.toString();
    }

    private String cleanCell(String value) {
        return StrUtil.blankToDefault(value, "")
            .replace("|", "\\|")
            .replace("\r", " ")
            .replace("\n", "<br>")
            .trim();
    }

    private HeadingParts splitHeading(String headingText) {
        String normalized = StrUtil.blankToDefault(headingText, "").trim();
        if (normalized.isBlank()) {
            return new HeadingParts("", "");
        }
        Matcher appendixMatcher = APPENDIX_CODE_PATTERN.matcher(normalized);
        if (appendixMatcher.matches()) {
            String code = appendixMatcher.group(1).trim();
            String title = StrUtil.blankToDefault(appendixMatcher.group(2), code).trim();
            return new HeadingParts(code, title);
        }
        Matcher chapterMatcher = CHAPTER_CODE_PATTERN.matcher(normalized);
        if (chapterMatcher.matches()) {
            return new HeadingParts(chapterMatcher.group(1).trim(), chapterMatcher.group(2).trim());
        }
        Matcher decimalMatcher = DECIMAL_CODE_PATTERN.matcher(normalized);
        if (decimalMatcher.matches()) {
            return new HeadingParts(decimalMatcher.group(1).trim(), decimalMatcher.group(2).trim());
        }
        Matcher chineseMatcher = CHINESE_OUTLINE_PATTERN.matcher(normalized);
        if (chineseMatcher.matches()) {
            return new HeadingParts(chineseMatcher.group(1).trim(), chineseMatcher.group(2).trim());
        }
        return new HeadingParts("", normalized);
    }

    private String listTitle(String value) {
        String title = LIST_MARKER_PATTERN.matcher(StrUtil.blankToDefault(value, "")).replaceFirst("").trim();
        if (title.isBlank()) {
            title = StrUtil.blankToDefault(value, "").trim();
        }
        if (title.length() > 120) {
            title = title.substring(0, 120).trim();
        }
        return title;
    }

    private String displayTitle(HeadingParts heading) {
        if (StrUtil.isBlank(heading.code())) {
            return heading.title();
        }
        if (heading.title().startsWith(heading.code())) {
            return heading.title();
        }
        return heading.code() + " " + heading.title();
    }

    private String joinSectionPath(String parentSectionPath, String displayTitle) {
        String parent = StrUtil.blankToDefault(parentSectionPath, "").trim();
        String current = StrUtil.blankToDefault(displayTitle, "").trim();
        if (parent.isBlank()) {
            return current;
        }
        if (current.isBlank()) {
            return parent;
        }
        return parent + " > " + current;
    }

    private String slug(String value) {
        String normalized = StrUtil.blankToDefault(value, "").trim();
        if (normalized.isBlank()) {
            return "node";
        }
        String slug = normalized
            .replaceAll("\\s+", "-")
            .replaceAll("[^\\p{IsHan}A-Za-z0-9_.-]", "");
        return slug.isBlank() ? "node" : slug;
    }

    private String stripExtension(String fileName) {
        String normalized = StrUtil.blankToDefault(fileName, "").trim();
        int index = normalized.lastIndexOf('.');
        return index > 0 ? normalized.substring(0, index) : normalized;
    }

    private String normalizeComparable(String value) {
        return StrUtil.blankToDefault(value, "")
            .replaceAll("^#+\\s*", "")
            .replaceAll("\\s+", "")
            .toLowerCase(Locale.ROOT);
    }

    public record DoclingStructureMapping(
        String markdown,
        List<DocumentStructureNodeCandidate> structureNodes
    ) {
    }

    private record HeadingParts(String code, String title) {
    }

    private record SectionFrame(DocumentStructureNodeCandidate node, int depth) {
    }

    private record ListItemFrame(DocumentStructureNodeCandidate node, int listDepth) {
    }

    private final class NodeBuilder {

        private final List<DocumentStructureNodeCandidate> nodes = new ArrayList<>();
        private final Deque<SectionFrame> sectionStack = new ArrayDeque<>();
        private final Deque<ListItemFrame> listStack = new ArrayDeque<>();
        private final Map<Integer, DocumentStructureNodeCandidate> lastChildByParent = new HashMap<>();
        private final Set<String> usedPaths = new HashSet<>();
        private final DocumentStructureNodeCandidate root;
        private final String documentTitle;
        private int nextNodeNo = 2;
        private int rootItemIndex = 0;
        private int tableSequence = 0;

        private NodeBuilder(String documentTitle) {
            this.documentTitle = documentTitle;
            this.root = new DocumentStructureNodeCandidate(
                1,
                DocumentStructureNodeTypeEnum.DOCUMENT.getCode(),
                null,
                0,
                0,
                0,
                "",
                documentTitle,
                documentTitle,
                "/document",
                "",
                "",
                null
            );
            nodes.add(root);
            usedPaths.add("/document");
        }

        private List<DocumentStructureNodeCandidate> nodes() {
            return nodes;
        }

        private void appendTitle(String value, StringBuilder renderedMarkdown) {
            if (sameAsDocumentTitle(value)) {
                appendRenderBlock(renderedMarkdown, "# " + value.trim());
                return;
            }
            appendSection(value, 1, renderedMarkdown);
        }

        private void appendSection(String headingText, int level, StringBuilder renderedMarkdown) {
            int requestedDepth = Math.max(1, level);
            while (!sectionStack.isEmpty() && sectionStack.peek().depth() >= requestedDepth) {
                sectionStack.pop();
            }
            int parentDepth = sectionStack.isEmpty() ? 0 : sectionStack.peek().depth();
            int depth = Math.min(requestedDepth, parentDepth + 1);
            DocumentStructureNodeCandidate parent = sectionStack.isEmpty() ? root : sectionStack.peek().node();

            HeadingParts heading = splitHeading(headingText);
            int nodeNo = nextNodeNo++;
            String segment = StrUtil.isNotBlank(heading.code()) ? slug(heading.code()) : slug(heading.title());
            String canonicalPath = uniquePath(parent.getCanonicalPath() + "/" + segment, nodeNo);
            String sectionPath = joinSectionPath(parent.getSectionPath(), displayTitle(heading));
            DocumentStructureNodeCandidate node = new DocumentStructureNodeCandidate(
                nodeNo,
                DocumentStructureNodeTypeEnum.SECTION.getCode(),
                parent.getNodeNo(),
                0,
                0,
                depth,
                heading.code(),
                heading.title(),
                headingText.trim(),
                canonicalPath,
                sectionPath,
                headingText.trim(),
                null
            );
            addChild(parent, node);
            nodes.add(node);
            sectionStack.push(new SectionFrame(node, depth));
            listStack.clear();
            appendRenderBlock(renderedMarkdown, "#".repeat(Math.min(Math.max(level, 1), 6)) + " " + headingText.trim());
        }

        private void appendBody(String value, String label, StringBuilder renderedMarkdown) {
            listStack.clear();
            DocumentStructureNodeCandidate target = currentSectionNode();
            appendContent(target, value);
            if ("code".equals(label)) {
                appendRenderBlock(renderedMarkdown, "```\n" + value.trim() + "\n```");
            }
            else if ("formula".equals(label)) {
                appendRenderBlock(renderedMarkdown, "$$\n" + value.trim() + "\n$$");
            }
            else {
                appendRenderBlock(renderedMarkdown, value.trim());
            }
        }

        private void appendListItem(String value,
                                    String marker,
                                    int listDepth,
                                    StringBuilder renderedMarkdown) {
            int normalizedListDepth = Math.max(1, listDepth);
            while (!listStack.isEmpty() && listStack.peek().listDepth() >= normalizedListDepth) {
                listStack.pop();
            }
            DocumentStructureNodeCandidate sectionNode = currentSectionNode();
            DocumentStructureNodeCandidate parent = listStack.isEmpty()
                ? sectionNode
                : listStack.peek().node();
            int depth = parent.getDepth() + 1;
            int itemIndex = nextItemIndex(sectionNode);
            String title = listTitle(value);
            boolean step = STEP_PATTERN.matcher(title).find();
            int nodeNo = nextNodeNo++;
            String canonicalPath = uniquePath(parent.getCanonicalPath() + "/item-" + itemIndex, nodeNo);
            String sectionPath = sectionNode.getSectionPath();
            DocumentStructureNodeCandidate node = new DocumentStructureNodeCandidate(
                nodeNo,
                step ? DocumentStructureNodeTypeEnum.STEP.getCode() : DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(),
                parent.getNodeNo(),
                0,
                0,
                depth,
                String.valueOf(itemIndex),
                title,
                value.trim(),
                canonicalPath,
                sectionPath,
                value.trim(),
                itemIndex
            );
            addChild(parent, node);
            nodes.add(node);
            listStack.push(new ListItemFrame(node, normalizedListDepth));
            appendContent(sectionNode, value);
            String indent = "  ".repeat(Math.max(0, normalizedListDepth - 1));
            String renderMarker = StrUtil.blankToDefault(marker, "-").trim();
            String renderValue = value.trim();
            if (LIST_MARKER_PATTERN.matcher(renderValue).find()) {
                appendRenderBlock(renderedMarkdown, indent + renderValue);
            }
            else {
                appendRenderBlock(renderedMarkdown, indent + renderMarker + " " + renderValue);
            }
        }

        private void appendTable(String caption,
                                 String markdown,
                                 StringBuilder renderedMarkdown) {
            if (StrUtil.isBlank(markdown)) {
                return;
            }
            listStack.clear();
            DocumentStructureNodeCandidate sectionNode = currentSectionNode();
            int itemIndex = nextItemIndex(sectionNode);
            tableSequence++;
            String title = StrUtil.isNotBlank(caption) ? caption.trim() : "表格 " + tableSequence;
            String content = StrUtil.isNotBlank(caption) ? caption.trim() + "\n\n" + markdown : markdown;
            int nodeNo = nextNodeNo++;
            String canonicalPath = uniquePath(sectionNode.getCanonicalPath() + "/table-" + itemIndex, nodeNo);
            DocumentStructureNodeCandidate node = new DocumentStructureNodeCandidate(
                nodeNo,
                DocumentStructureNodeTypeEnum.LIST_ITEM.getCode(),
                sectionNode.getNodeNo(),
                0,
                0,
                sectionNode.getDepth() + 1,
                String.valueOf(itemIndex),
                title,
                title,
                canonicalPath,
                sectionNode.getSectionPath(),
                content,
                itemIndex
            );
            addChild(sectionNode, node);
            nodes.add(node);
            appendContent(sectionNode, content);
            appendRenderBlock(renderedMarkdown, content);
        }

        private void appendPicture(String caption, StringBuilder renderedMarkdown) {
            if (StrUtil.isBlank(caption)) {
                return;
            }
            listStack.clear();
            appendContent(currentSectionNode(), caption);
            appendRenderBlock(renderedMarkdown, "![" + caption.trim() + "]()");
        }

        private DocumentStructureNodeCandidate currentSectionNode() {
            return sectionStack.isEmpty() ? root : sectionStack.peek().node();
        }

        private int nextItemIndex(DocumentStructureNodeCandidate sectionNode) {
            if (sectionNode == root) {
                rootItemIndex++;
                return rootItemIndex;
            }
            return countChildren(sectionNode.getNodeNo()) + 1;
        }

        private int countChildren(int parentNodeNo) {
            int count = 0;
            for (DocumentStructureNodeCandidate node : nodes) {
                if (node.getParentNodeNo() != null && node.getParentNodeNo() == parentNodeNo) {
                    count++;
                }
            }
            return count;
        }

        private void addChild(DocumentStructureNodeCandidate parent, DocumentStructureNodeCandidate node) {
            DocumentStructureNodeCandidate previous = lastChildByParent.get(parent.getNodeNo());
            if (previous != null) {
                previous.setNextSiblingNodeNo(node.getNodeNo());
                node.setPrevSiblingNodeNo(previous.getNodeNo());
            }
            else {
                node.setPrevSiblingNodeNo(0);
            }
            node.setNextSiblingNodeNo(0);
            lastChildByParent.put(parent.getNodeNo(), node);
        }

        private void appendContent(DocumentStructureNodeCandidate node, String value) {
            String normalized = StrUtil.blankToDefault(value, "").trim();
            if (normalized.isBlank()) {
                return;
            }
            String content = StrUtil.blankToDefault(node.getContentText(), "").trim();
            node.setContentText(content.isBlank() ? normalized : content + "\n" + normalized);
        }

        private void appendRenderBlock(StringBuilder renderedMarkdown, String value) {
            String normalized = StrUtil.blankToDefault(value, "").trim();
            if (normalized.isBlank()) {
                return;
            }
            if (!renderedMarkdown.isEmpty()) {
                renderedMarkdown.append("\n\n");
            }
            renderedMarkdown.append(normalized);
        }

        private String uniquePath(String canonicalPath, int nodeNo) {
            String path = canonicalPath;
            if (usedPaths.add(path)) {
                return path;
            }
            int attempt = 1;
            path = canonicalPath + "-" + nodeNo;
            while (!usedPaths.add(path)) {
                path = canonicalPath + "-" + nodeNo + "-" + attempt++;
            }
            return path;
        }

        private boolean sameAsDocumentTitle(String headingText) {
            String documentBase = normalizeComparable(stripExtension(documentTitle));
            if (documentBase.isBlank()) {
                return false;
            }
            String fullHeading = normalizeComparable(headingText);
            String headingTitle = normalizeComparable(splitHeading(headingText).title());
            return documentBase.equals(fullHeading) || documentBase.equals(headingTitle);
        }
    }
}
