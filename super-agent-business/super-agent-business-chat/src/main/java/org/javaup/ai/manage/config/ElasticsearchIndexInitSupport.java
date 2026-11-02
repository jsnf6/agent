package org.javaup.ai.manage.config;

import org.elasticsearch.client.ResponseException;

import java.io.IOException;

/**
 * @program: 企业级别深度设计 AI Agent。
 * @description: Elasticsearch 索引初始化异常判定
 * @author: 阿星不是程序员
 **/
final class ElasticsearchIndexInitSupport {

    private ElasticsearchIndexInitSupport() {
    }

    /**
     * 判断异常是否由认证/授权失败引起。这类失败与分词器无关，不应触发 standard 回退。
     */
    static boolean isAuthenticationFailure(IOException exception) {
        Throwable current = exception;
        while (current != null) {
            if (current instanceof ResponseException responseException
                && responseException.getResponse() != null) {
                int statusCode = responseException.getResponse().getStatusLine().getStatusCode();
                if (statusCode == 401 || statusCode == 403) {
                    return true;
                }
            }
            String message = current.getMessage();
            if (message != null && (message.contains("status: 401") || message.contains("status: 403"))) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }
}
