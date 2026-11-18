package org.javaup.ai.manage.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * @program: 企业级别深度设计 AI Agent。添加  微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置属性
 * @author:
 **/

@Data
@ConfigurationProperties(prefix = "app.manage")
public class DocumentManageProperties {

    private Minio minio = new Minio();

    private Kafka kafka = new Kafka();

    private Chunk chunk = new Chunk();

    private StructureParsing structureParsing = new StructureParsing();

    private PgVector pgVector = new PgVector();

    private Elasticsearch elasticsearch = new Elasticsearch();

    private Neo4j neo4j = new Neo4j();

    private KnowledgeBinding knowledgeBinding = new KnowledgeBinding();

    private KnowledgeRoute knowledgeRoute = new KnowledgeRoute();

    @Data
    public static class Minio {
        private String endpoint = "http://127.0.0.1:9000";
        private String accessKey = "minioadmin";
        private String secretKey = "minioadmin";
        private String bucketName = "super-agent-document";
        private String objectPrefix = "rag/document";
        private String parsedTextPrefix = "rag/parsed-text";
    }

    @Data
    public static class Kafka {
        private String parseTopic = "super-agent-document-parse-route";
        private String indexTopic = "super-agent-document-index-build";
        private String groupId = "super-agent-document-manage";
        private Boolean autoCreateTopics = Boolean.TRUE;
    }

    @Data
    public static class Chunk {
        private Integer recursiveMaxChars = 800;
        private Integer recursiveOverlapChars = 120;
        private Integer semanticMaxChars = 700;
        private Integer semanticMinChars = 240;
        private Double semanticSimilarityThreshold = 0.18D;
        private Boolean llmEnabled = Boolean.FALSE;
        private Integer llmMaxChars = 3500;
        private Boolean recommendLlmWhenLowQuality = Boolean.TRUE;
    }

    @Data
    public static class StructureParsing {

        private String provider = "docling";

        private Boolean llmDisambiguationEnabled = Boolean.TRUE;

        private Integer maxAmbiguousSignalsPerCall = 8;

        private Integer contextWindowLines = 2;

        private Integer maxPlainHeadingChars = 32;

        private Double ambiguityConfidenceFloor = 0.45D;

        private Double ambiguityConfidenceCeil = 0.80D;

        private Docling docling = new Docling();
    }

    @Data
    public static class Docling {

        private String baseUrl = "http://127.0.0.1:5001";

        private String convertPath = "/v1/convert/file";

        private Integer connectTimeoutMillis = 5000;

        private Integer readTimeoutMillis = 300000;

        private Boolean doOcr = Boolean.FALSE;

        private String ocrEngine = "easyocr";

        private String pdfBackend = "";

        private String tableMode = "accurate";

        private Boolean fallbackToTika = Boolean.TRUE;

        private List<String> fileTypes = new ArrayList<>(List.of("pdf", "docx", "html", "md"));
    }

    @Data
    public static class PgVector {

        private Boolean enabled = Boolean.TRUE;

        private String host = "127.0.0.1";

        private Integer port = 5432;

        private String database = "super_agent_pgvector";

        private String schema = "public";

        private String username = "postgres";

        private String password = "postgres";

        private String poolName = "super-agent-manage-pgvector-hikari";

        private Integer maximumPoolSize = 5;

        private Integer minimumIdle = 1;
    }

    @Data
    public static class Elasticsearch {

        private Boolean enabled = Boolean.TRUE;

        private List<String> uris = new ArrayList<>(List.of("http://127.0.0.1:9200"));

        private String username = "elastic";

        private String password = "elastic";

        private String indexName = "super_agent_document_keyword";

        private String analyzer = "ik_max_word";

        private String searchAnalyzer = "ik_smart";

        private String navigationIndexName = "super_agent_document_navigation";

        private String routeIndexName = "super_agent_knowledge_route";

        private Integer connectTimeoutMillis = 3000;

        private Integer socketTimeoutMillis = 5000;
    }

    @Data
    public static class Neo4j {

        private Boolean enabled = Boolean.FALSE;

        private String uri = "bolt://127.0.0.1:7687";

        private String username = "neo4j";

        private String password = "neo4j";

        private String database = "neo4j";

        private Integer queryTimeoutSeconds = 5;
    }

    @Data
    public static class KnowledgeBinding {

        private Boolean enabled = Boolean.TRUE;

        private Double cosineFloor = 0.25D;

        private Double cosineCeil = 0.70D;

        private Double scopeWeightTitle = 0.40D;

        private Double scopeWeightHeading = 0.25D;

        private Double scopeWeightSummary = 0.20D;

        private Double scopeWeightLexical = 0.15D;

        private Double scopeHighConfidenceScore = 0.55D;

        private Double scopeHighConfidenceGap = 0.08D;

        private Double scopeClearMinScore = 0.42D;

        private Double scopeClearRelativeGap = 0.30D;

        private Double scopeClearGap = 0.10D;

        private Double scopeEvidenceMinScore = 0.38D;

        private Double scopeEvidenceGap = 0.10D;

        private Double topicMinScore = 0.58D;

        private Integer topicMaxCount = 3;

        private Integer headingViewLimit = 12;

        private Integer sectionEvidenceLimit = 30;

        private Integer sectionLeadChars = 300;

        private Integer sectionItemLimit = 8;
    }

    @Data
    public static class KnowledgeRoute {

        private Double cosineFloor = 0.25D;

        private Double cosineCeil = 0.80D;

        private Double scopeSemanticWeight = 0.70D;

        private Double scopeLexicalWeight = 0.30D;

        private Double topicSemanticWeight = 0.75D;

        private Double topicLexicalWeight = 0.25D;

        private Double topicOwnWeight = 0.70D;

        private Double topicParentWeight = 0.30D;

        private Double documentSemanticWeight = 0.55D;

        private Double documentLexicalWeight = 0.20D;

        private Double documentScopeWeight = 0.15D;

        private Double documentTopicWeight = 0.10D;

        private Double confidenceDocumentWeight = 0.55D;

        private Double confidenceGapWeight = 0.25D;

        private Double confidenceScopeWeight = 0.12D;

        private Double confidenceTopicWeight = 0.08D;

        private Double esLexicalSaturation = 20D;

        private Double successThreshold = 0.55D;

        private Double clarificationRelativeGap = 0.15D;

        private Integer scopeCandidateLimit = 3;

        private Integer topicScopeLimit = 3;

        private Integer topicCandidateLimit = 8;

        private Integer documentScopeLimit = 3;

        private Integer documentCandidateLimit = 5;
    }
}
