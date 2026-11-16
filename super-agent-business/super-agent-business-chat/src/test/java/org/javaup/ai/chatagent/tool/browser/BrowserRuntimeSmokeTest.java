package org.javaup.ai.chatagent.tool.browser;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.PlaywrightException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.springframework.ai.chat.model.ToolContext;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * 需要本地或容器内已安装 Chromium。默认不执行，设置 BROWSER_SMOKE_URL 后启用。
 */
@EnabledIfEnvironmentVariable(named = "BROWSER_SMOKE_URL", matches = ".+")
class BrowserRuntimeSmokeTest {

    @Test
    void shouldOpenPublicPageAndBlockPrivateNavigation() {
        BrowserProperties properties = new BrowserProperties();
        properties.setHeadless(true);
        properties.setNoSandbox(false);

        try (BrowserSessionManager sessionManager = new BrowserSessionManager(properties)) {
            String title = sessionManager.execute("browser-runtime-smoke", page -> {
                page.navigate(System.getenv("BROWSER_SMOKE_URL"));
                return page.title();
            });

            assertNotNull(title);
            assertThrows(PlaywrightException.class, () -> sessionManager.execute(
                "browser-runtime-smoke",
                page -> page.navigate("http://127.0.0.1:9082/actuator/health")
            ));
        }
    }

    @Test
    void shouldExposeBrowserToolSnapshotAndClose() {
        BrowserProperties properties = new BrowserProperties();
        properties.setHeadless(true);
        properties.setNoSandbox(false);

        try (BrowserSessionManager sessionManager = new BrowserSessionManager(properties)) {
            BrowserTools browserTools = new BrowserTools(properties, sessionManager, new ObjectMapper());
            ToolContext toolContext = new ToolContext(Map.of(
                ChatContextKeys.CONVERSATION_ID,
                "browser-tool-runtime-smoke"
            ));

            String opened = browserTools.browserOpen(System.getenv("BROWSER_SMOKE_URL"), toolContext);
            String snapshot = browserTools.browserSnapshot(toolContext);
            String closed = browserTools.browserClose(toolContext);

            assertTrue(opened.contains("\"title\""));
            assertTrue(opened.contains("\"elements\""));
            assertTrue(snapshot.contains("\"sessionId\""));
            assertTrue(closed.contains("\"closed\":true"));
        }
    }
}
