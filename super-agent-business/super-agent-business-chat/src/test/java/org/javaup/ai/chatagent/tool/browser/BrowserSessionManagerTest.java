package org.javaup.ai.chatagent.tool.browser;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BrowserSessionManagerTest {

    private BrowserProperties properties;

    private BrowserSessionManager sessionManager;

    @BeforeEach
    void setUp() {
        properties = new BrowserProperties();
        sessionManager = new BrowserSessionManager(properties);
    }

    @AfterEach
    void tearDown() {
        sessionManager.close();
    }

    @Test
    void shouldRejectUnsupportedNavigationScheme() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateUrl("ftp://example.com/file"));

        assertTrue(exception.getMessage().contains("不支持的 url 协议"));
    }

    @Test
    void shouldRejectNavigationUrlWithUserInfo() {
        IllegalArgumentException exception = assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateUrl("https://user:password@example.com"));

        assertTrue(exception.getMessage().contains("用户信息"));
    }

    @Test
    void shouldRejectPrivateIpv4Addresses() {
        List<String> privateUrls = List.of(
            "http://127.0.0.1",
            "http://10.0.0.1",
            "http://172.16.0.1",
            "http://192.168.1.1",
            "http://169.254.169.254/latest/meta-data",
            "http://100.64.0.1",
            "http://198.18.0.1",
            "http://0.1.2.3",
            "http://240.0.0.1"
        );

        for (String privateUrl : privateUrls) {
            assertThrows(IllegalArgumentException.class, () -> sessionManager.validateUrl(privateUrl), privateUrl);
        }
    }

    @Test
    void shouldRejectPrivateIpv6Addresses() {
        assertThrows(IllegalArgumentException.class, () -> sessionManager.validateUrl("http://[::1]/"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.validateUrl("http://[::ffff:127.0.0.1]/"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.validateUrl("http://[fc00::1]/"));
    }

    @Test
    void shouldAllowPublicUrlWhenPrivateNetworkBlockingIsDisabled() {
        properties.setBlockPrivateNetwork(false);

        assertEquals("https://example.com/path?q=1",
            sessionManager.validateUrl("https://example.com/path?q=1"));
    }

    @Test
    void shouldEnforceAllowedHostsForNavigation() {
        properties.setBlockPrivateNetwork(false);
        properties.setAllowedHosts(List.of("example.com", "*.trusted.com"));

        assertDoesNotThrow(() -> sessionManager.validateUrl("https://example.com"));
        assertDoesNotThrow(() -> sessionManager.validateUrl("https://api.trusted.com/docs"));
        assertThrows(IllegalArgumentException.class, () -> sessionManager.validateUrl("https://evil.com"));
    }

    @Test
    void shouldAllowPrivateHostWhenItIsExplicitlyWhitelisted() {
        properties.setAllowedHosts(List.of("10.0.0.5"));

        assertDoesNotThrow(() -> sessionManager.validateUrl("http://10.0.0.5/portal"));
        assertDoesNotThrow(() -> sessionManager.validateSubresourceUrl("http://10.0.0.5/assets/app.js"));
    }

    @Test
    void shouldAllowBrowserInternalSubresourceSchemes() {
        assertDoesNotThrow(() -> sessionManager.validateSubresourceUrl("data:text/plain,hello"));
        assertDoesNotThrow(() -> sessionManager.validateSubresourceUrl("blob:https://example.com/asset-id"));
        assertDoesNotThrow(() -> sessionManager.validateSubresourceUrl("about:blank"));
    }

    @Test
    void shouldRejectDangerousSubresourceSchemesAndPrivateAddresses() {
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateSubresourceUrl("file:///etc/passwd"));
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateSubresourceUrl("ftp://example.com/file"));
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateSubresourceUrl("http://169.254.169.254/latest/meta-data"));
    }

    @Test
    void shouldAllowPublicSubresourceWhenPrivateNetworkBlockingIsDisabled() {
        properties.setBlockPrivateNetwork(false);

        assertDoesNotThrow(() -> sessionManager.validateSubresourceUrl("https://cdn.example.com/app.js"));
    }

    @Test
    void shouldValidateWebSocketTargets() {
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateWebSocketUrl("ws://127.0.0.1:8080/socket"));
        assertThrows(IllegalArgumentException.class,
            () -> sessionManager.validateWebSocketUrl("https://example.com/socket"));

        properties.setBlockPrivateNetwork(false);
        assertDoesNotThrow(() -> sessionManager.validateWebSocketUrl("wss://example.com/socket"));
    }
}
