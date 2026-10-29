package org.javaup.ai.manage.service.docling;

import cn.hutool.core.util.StrUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.manage.config.DocumentManageProperties;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.MediaType;
import org.springframework.http.client.MultipartBodyBuilder;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: Docling Serve 客户端
 * @author: 阿星不是程序员
 **/

@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.manage.structure-parsing", name = "provider",
    havingValue = "docling", matchIfMissing = true)
public class DoclingServeClient {

    private final RestClient restClient;
    private final ObjectMapper objectMapper;
    private final DocumentManageProperties properties;

    public DoclingServeClient(@Qualifier("doclingRestClient") RestClient restClient,
                              ObjectMapper objectMapper,
                              DocumentManageProperties properties) {
        this.restClient = restClient;
        this.objectMapper = objectMapper;
        this.properties = properties;
    }

    public DoclingConversionResult convert(byte[] bytes,
                                           String originalFileName,
                                           String mimeType) {
        DocumentManageProperties.Docling docling = properties.getStructureParsing().getDocling();
        String uploadFileName = safeUploadFileName(originalFileName, mimeType);
        MultipartBodyBuilder bodyBuilder = new MultipartBodyBuilder();
        bodyBuilder.part("files", new ByteArrayResource(bytes) {
                @Override
                public String getFilename() {
                    return uploadFileName;
                }
            })
            .contentType(resolveMediaType(mimeType));
        bodyBuilder.part("to_formats", "json");
        bodyBuilder.part("to_formats", "md");
        bodyBuilder.part("image_export_mode", "placeholder");
        bodyBuilder.part("do_ocr", Boolean.TRUE.equals(docling.getDoOcr()) ? "true" : "false");
        if (Boolean.TRUE.equals(docling.getDoOcr()) && StrUtil.isNotBlank(docling.getOcrEngine())) {
            bodyBuilder.part("ocr_engine", docling.getOcrEngine().trim());
        }
        if (StrUtil.isNotBlank(docling.getPdfBackend())) {
            bodyBuilder.part("pdf_backend", docling.getPdfBackend().trim());
        }
        if (StrUtil.isNotBlank(docling.getTableMode())) {
            bodyBuilder.part("table_mode", docling.getTableMode().trim());
        }

        JsonNode response = restClient.post()
            .uri(StrUtil.blankToDefault(docling.getConvertPath(), "/v1/convert/file"))
            .contentType(MediaType.MULTIPART_FORM_DATA)
            .body(bodyBuilder.build())
            .retrieve()
            .body(JsonNode.class);
        return parseResponse(response);
    }

    private DoclingConversionResult parseResponse(JsonNode response) {
        if (response == null || response.isNull()) {
            throw new IllegalStateException("Docling 返回为空");
        }
        String status = text(response, "status");
        List<String> errors = readErrors(response.path("errors"));
        JsonNode documentNode = response.path("document");
        if (!documentNode.isObject()) {
            if ("failure".equalsIgnoreCase(status)) {
                throw new IllegalStateException("Docling 解析失败: " + String.join("; ", errors));
            }
            throw new IllegalStateException("Docling 响应缺少 document 字段");
        }

        JsonNode doclingDocument = readJsonContent(documentNode.path("json_content"));
        String markdown = text(documentNode, "md_content");
        String textContent = text(documentNode, "text_content");
        if (StrUtil.isBlank(markdown) && StrUtil.isBlank(textContent) && doclingDocument == null) {
            throw new IllegalStateException("Docling 响应未返回可用内容: " + String.join("; ", errors));
        }
        if ("failure".equalsIgnoreCase(status)) {
            throw new IllegalStateException("Docling 解析失败: " + String.join("; ", errors));
        }
        if (!errors.isEmpty()) {
            log.warn("Docling 解析返回部分错误，继续使用已生成内容: {}", errors);
        }
        return new DoclingConversionResult(doclingDocument, markdown, textContent, status, errors);
    }

    private JsonNode readJsonContent(JsonNode jsonContent) {
        if (jsonContent == null || jsonContent.isMissingNode() || jsonContent.isNull()) {
            return null;
        }
        try {
            if (jsonContent.isObject()) {
                return jsonContent;
            }
            if (jsonContent.isTextual() && StrUtil.isNotBlank(jsonContent.asText())) {
                return objectMapper.readTree(jsonContent.asText());
            }
        }
        catch (Exception exception) {
            log.warn("Docling json_content 解析失败: {}", exception.getMessage());
        }
        return null;
    }

    private List<String> readErrors(JsonNode errorsNode) {
        List<String> errors = new ArrayList<>();
        if (errorsNode == null || !errorsNode.isArray()) {
            return errors;
        }
        for (JsonNode error : errorsNode) {
            if (error.isTextual()) {
                errors.add(error.asText());
                continue;
            }
            String message = text(error, "error");
            if (StrUtil.isBlank(message)) {
                message = text(error, "message");
            }
            if (StrUtil.isNotBlank(message)) {
                errors.add(message);
            }
        }
        return errors;
    }

    private String text(JsonNode node, String fieldName) {
        if (node == null || !node.has(fieldName) || node.get(fieldName).isNull()) {
            return "";
        }
        return node.get(fieldName).asText("");
    }

    private MediaType resolveMediaType(String mimeType) {
        if (StrUtil.isNotBlank(mimeType)) {
            try {
                return MediaType.parseMediaType(mimeType);
            }
            catch (Exception exception) {
                log.debug("无法识别文档 MIME 类型: {}", mimeType);
            }
        }
        return MediaType.APPLICATION_OCTET_STREAM;
    }

    /**
     * Docling 通过文件名后缀判断输入格式。中文文件名在 multipart 中会被编码成
     * RFC 2047 形式，服务端取不到扩展名，最终返回 status=skipped 且内容为空。
     * 这里固定使用 ASCII 文件名，原始文件名仍由调用方用于生成文档标题。
     */
    private String safeUploadFileName(String originalFileName, String mimeType) {
        String extension = "";
        String fileName = StrUtil.blankToDefault(originalFileName, "").trim();
        int dot = fileName.lastIndexOf('.');
        if (dot >= 0 && dot < fileName.length() - 1) {
            String candidate = fileName.substring(dot + 1).trim().toLowerCase(Locale.ROOT);
            if (candidate.matches("[a-z0-9]{1,8}")) {
                extension = "." + candidate;
            }
        }
        if (extension.isEmpty() && StrUtil.isNotBlank(mimeType)) {
            extension = switch (mimeType.trim().toLowerCase(Locale.ROOT)) {
                case "application/pdf" -> ".pdf";
                case "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> ".docx";
                case "text/html" -> ".html";
                case "text/markdown", "text/x-markdown" -> ".md";
                default -> "";
            };
        }
        return "document" + extension;
    }

    public record DoclingConversionResult(
        JsonNode doclingDocument,
        String markdown,
        String text,
        String status,
        List<String> errors
    ) {
    }
}
