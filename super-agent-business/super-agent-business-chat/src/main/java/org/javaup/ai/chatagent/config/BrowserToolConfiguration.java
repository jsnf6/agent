package org.javaup.ai.chatagent.config;


import org.javaup.ai.chatagent.tool.browser.BrowserTools;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@ConditionalOnProperty(prefix = "app.browser", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BrowserToolConfiguration {

    @Bean
    public ToolCallbackProvider browserToolCallbackProvider(BrowserTools browserTools) {
        return MethodToolCallbackProvider.builder()
                .toolObjects(browserTools)
                .build();
    }
}
