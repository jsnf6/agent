package org.javaup.ai.manage.config;

import cn.hutool.core.util.StrUtil;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/**
 * @program: 企业级别深度设计 AI Agent。添加 阿星不是程序员 微信，添加时备注 super 来获取项目的完整资料
 * @description: 配置类
 * @author: 阿星不是程序员
 **/

@Configuration
@EnableConfigurationProperties(DocumentManageProperties.class)
@ConditionalOnProperty(prefix = "app.manage.structure-parsing", name = "provider",
    havingValue = "docling", matchIfMissing = true)
public class DoclingDocumentParserConfiguration {

    @Bean(name = "doclingRestClient")
    public RestClient doclingRestClient(DocumentManageProperties properties) {
        DocumentManageProperties.Docling docling = properties.getStructureParsing().getDocling();
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(docling.getConnectTimeoutMillis());
        requestFactory.setReadTimeout(docling.getReadTimeoutMillis());
        return RestClient.builder()
            .baseUrl(StrUtil.blankToDefault(docling.getBaseUrl(), "http://127.0.0.1:5001"))
            .requestFactory(requestFactory)
            .build();
    }
}
