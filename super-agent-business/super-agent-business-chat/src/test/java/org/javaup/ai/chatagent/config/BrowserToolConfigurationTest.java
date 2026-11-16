package org.javaup.ai.chatagent.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.javaup.ai.chatagent.tool.browser.BrowserProperties;
import org.javaup.ai.chatagent.tool.browser.BrowserSessionManager;
import org.javaup.ai.chatagent.tool.browser.BrowserTools;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

class BrowserToolConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
        .withUserConfiguration(BrowserToolConfiguration.class)
        .withBean(BrowserTools.class, () -> new BrowserTools(
            new BrowserProperties(),
            mock(BrowserSessionManager.class),
            new ObjectMapper()
        ));

    @Test
    void shouldCreateProviderWhenBrowserIsEnabled() {
        contextRunner
            .withPropertyValues("app.browser.enabled=true")
            .run(context -> assertThat(context).hasSingleBean(ToolCallbackProvider.class));
    }

    @Test
    void shouldNotCreateProviderWhenBrowserIsDisabled() {
        contextRunner
            .withPropertyValues("app.browser.enabled=false")
            .run(context -> assertThat(context).doesNotHaveBean(ToolCallbackProvider.class));
    }
}
