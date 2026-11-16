package org.javaup.ai.chatagent.tool.browser;

import cn.hutool.core.util.StrUtil;
import com.microsoft.playwright.Browser;
import com.microsoft.playwright.BrowserContext;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Playwright;
import com.microsoft.playwright.Route;
import com.microsoft.playwright.WebSocketFrame;
import com.microsoft.playwright.WebSocketRoute;
import com.microsoft.playwright.options.ServiceWorkerPolicy;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Function;

/**
 * 按会话隔离 Playwright 浏览器、BrowserContext 和 Page。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.browser", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BrowserSessionManager implements AutoCloseable {

    private static final String DEFAULT_SESSION_ID = "anonymous";

    private final BrowserProperties properties;

    private final Map<String, BrowserSession> sessions = new ConcurrentHashMap<>();

    private final Object browserLock = new Object();

    private final ScheduledExecutorService cleanupExecutor = Executors.newSingleThreadScheduledExecutor(runnable -> {
        Thread thread = new Thread(runnable, "browser-session-cleaner");
        thread.setDaemon(true);
        return thread;
    });

    private volatile Playwright playwright;

    private volatile Browser browser;

    private volatile boolean closed;

    public BrowserSessionManager(BrowserProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void startCleanupTask() {
        if (!properties.isEnabled()) {
            return;
        }
        long interval = Math.max(10, properties.getCleanupIntervalSeconds());
        cleanupExecutor.scheduleWithFixedDelay(this::cleanupIdleSessions, interval, interval, TimeUnit.SECONDS);
    }

    public <T> T execute(String conversationId, Function<Page, T> action) {
        if (action == null) {
            throw new IllegalArgumentException("浏览器操作不能为空");
        }
        ensureEnabled();
        String sessionId = normalizeSessionId(conversationId);
        BrowserSession session = sessions.compute(sessionId, (key, current) -> {
            if (current != null && current.usable()) {
                return current;
            }
            if (current != null) {
                current.closeQuietly();
            }
            return createSession(key);
        });
        evictExcessSessions(sessionId);
        return session.execute(action);
    }

    public void closeSession(String conversationId) {
        String sessionId = normalizeSessionId(conversationId);
        BrowserSession session = sessions.remove(sessionId);
        if (session != null) {
            session.closeQuietly();
            log.info("浏览器会话已关闭: sessionId={}", sessionId);
        }
    }

    /**
     * 校验初始 URL；BrowserContext 的导航请求拦截会再次校验重定向目标。
     */
    public String validateUrl(String rawUrl) {
        URI uri = parseUri(rawUrl, "url");
        if (!uri.isAbsolute() || StrUtil.isBlank(uri.getScheme())) {
            throw new IllegalArgumentException("url 必须包含 http 或 https 协议");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        List<String> allowedSchemes = Optional.ofNullable(properties.getAllowedSchemes()).orElse(List.of());
        if (allowedSchemes.stream().noneMatch(item -> item != null && item.equalsIgnoreCase(scheme))) {
            throw new IllegalArgumentException("不支持的 url 协议: " + scheme);
        }
        if (StrUtil.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("url 缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("url 不允许包含用户信息");
        }

        String host = normalizeHost(uri);
        List<String> allowedHosts = Optional.ofNullable(properties.getAllowedHosts()).orElse(List.of());
        boolean explicitlyAllowed = isExplicitlyAllowed(host);
        if (!allowedHosts.isEmpty() && !explicitlyAllowed) {
            throw new IllegalArgumentException("目标主机不在浏览器白名单中: " + host);
        }
        if (!explicitlyAllowed && properties.isBlockPrivateNetwork()) {
            assertPublicHost(host);
        }
        return uri.toString();
    }

    /**
     * 校验页面子资源请求。data/blob/about 等浏览器内部协议放行；http/https 仍阻断私网地址。
     * 子资源不强制命中 allowed-hosts，避免白名单页面引用 CDN 时无法加载静态资源。
     */
    void validateSubresourceUrl(String rawUrl) {
        URI uri = parseUri(rawUrl, "请求 url");
        if (!uri.isAbsolute() || StrUtil.isBlank(uri.getScheme())) {
            throw new IllegalArgumentException("请求 url 格式不正确");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"http".equals(scheme) && !"https".equals(scheme)) {
            if ("data".equals(scheme) || "blob".equals(scheme) || "about".equals(scheme)) {
                return;
            }
            throw new IllegalArgumentException("不支持的子资源协议: " + scheme);
        }
        validateNetworkHost(uri);
    }

    /**
     * 校验 WebSocket 连接目标，避免页面通过 ws/wss 访问私网服务。
     */
    void validateWebSocketUrl(String rawUrl) {
        URI uri = parseUri(rawUrl, "WebSocket url");
        if (!uri.isAbsolute() || StrUtil.isBlank(uri.getScheme())) {
            throw new IllegalArgumentException("WebSocket url 格式不正确");
        }
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        if (!"ws".equals(scheme) && !"wss".equals(scheme)) {
            throw new IllegalArgumentException("不支持的 WebSocket 协议: " + scheme);
        }
        validateNetworkHost(uri);
    }

    private void validateNetworkHost(URI uri) {
        if (StrUtil.isBlank(uri.getHost())) {
            throw new IllegalArgumentException("请求 url 缺少主机名");
        }
        if (uri.getUserInfo() != null) {
            throw new IllegalArgumentException("请求 url 不允许包含用户信息");
        }
        String host = normalizeHost(uri);
        if (!isExplicitlyAllowed(host) && properties.isBlockPrivateNetwork()) {
            assertPublicHost(host);
        }
    }

    private String normalizeHost(URI uri) {
        String host = uri.getHost();
        if (host == null) {
            return "";
        }
        String normalized = host.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("[") && normalized.endsWith("]")) {
            return normalized.substring(1, normalized.length() - 1);
        }
        return normalized;
    }

    private URI parseUri(String rawUrl, String label) {
        if (StrUtil.isBlank(rawUrl)) {
            throw new IllegalArgumentException(label + " 不能为空");
        }
        try {
            return URI.create(rawUrl.trim());
        }
        catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException(label + " 格式不正确", exception);
        }
    }

    private boolean isExplicitlyAllowed(String host) {
        List<String> allowedHosts = Optional.ofNullable(properties.getAllowedHosts()).orElse(List.of());
        return allowedHosts.stream()
            .filter(StrUtil::isNotBlank)
            .anyMatch(pattern -> matchesHost(host, pattern));
    }

    private BrowserSession createSession(String sessionId) {
        Browser currentBrowser = ensureBrowser();
        Browser.NewContextOptions contextOptions = new Browser.NewContextOptions()
            .setViewportSize(Math.max(320, properties.getViewportWidth()), Math.max(240, properties.getViewportHeight()))
            .setServiceWorkers(ServiceWorkerPolicy.BLOCK)
            .setAcceptDownloads(false);
        if (StrUtil.isNotBlank(properties.getLocale())) {
            contextOptions.setLocale(properties.getLocale());
        }
        if (StrUtil.isNotBlank(properties.getUserAgent())) {
            contextOptions.setUserAgent(properties.getUserAgent());
        }

        BrowserContext context = currentBrowser.newContext(contextOptions);
        context.route("**/*", this::handleRequestRoute);
        context.routeWebSocket("**/*", this::handleWebSocketRoute);
        Page page = context.newPage();
        page.setDefaultTimeout(properties.getTimeoutMs());
        page.setDefaultNavigationTimeout(properties.getTimeoutMs());
        log.info("浏览器会话已创建: sessionId={}, headless={}", sessionId, properties.isHeadless());
        return new BrowserSession(sessionId, context, page);
    }

    private void handleRequestRoute(Route route) {
        String url = route.request().url();
        try {
            if (route.request().isNavigationRequest()) {
                validateUrl(url);
            }
            else {
                validateSubresourceUrl(url);
            }
            route.resume();
        }
        catch (RuntimeException exception) {
            log.warn("浏览器请求被拦截: navigation={}, url={}, reason={}",
                route.request().isNavigationRequest(), url, exception.getMessage());
            route.abort("blockedbyclient");
        }
    }

    private void handleWebSocketRoute(WebSocketRoute route) {
        try {
            validateWebSocketUrl(route.url());
            WebSocketRoute server = route.connectToServer();
            route.onMessage(frame -> forwardWebSocketFrame(server, frame));
            server.onMessage(frame -> forwardWebSocketFrame(route, frame));
        }
        catch (RuntimeException exception) {
            log.warn("浏览器 WebSocket 被拦截: url={}, reason={}", route.url(), exception.getMessage());
            route.close();
        }
    }

    private void forwardWebSocketFrame(WebSocketRoute target, WebSocketFrame frame) {
        String text = frame.text();
        if (text != null) {
            target.send(text);
        }
        else {
            target.send(frame.binary());
        }
    }

    private Browser ensureBrowser() {
        Browser current = browser;
        if (current != null && current.isConnected()) {
            return current;
        }
        synchronized (browserLock) {
            if (closed) {
                throw new IllegalStateException("浏览器管理器已关闭");
            }
            if (browser != null && browser.isConnected()) {
                return browser;
            }
            try {
                if (playwright == null) {
                    playwright = Playwright.create();
                }
                List<String> args = new ArrayList<>();
                if (properties.getLaunchArgs() != null) {
                    args.addAll(properties.getLaunchArgs());
                }
                if (properties.isNoSandbox() && !args.contains("--no-sandbox")) {
                    args.add("--no-sandbox");
                }
                BrowserType.LaunchOptions launchOptions = new BrowserType.LaunchOptions()
                    .setHeadless(properties.isHeadless())
                    .setChromiumSandbox(!properties.isNoSandbox())
                    .setArgs(args)
                    .setTimeout(properties.getTimeoutMs());
                browser = playwright.chromium().launch(launchOptions);
                log.info("Chromium 浏览器已启动: headless={}, args={}", properties.isHeadless(), args);
                return browser;
            }
            catch (RuntimeException exception) {
                closePlaywrightQuietly();
                throw new IllegalStateException(
                    "Chromium 启动失败，请确认已执行 Playwright install chromium，且容器包含浏览器依赖",
                    exception
                );
            }
        }
    }

    private void cleanupIdleSessions() {
        try {
            Duration ttl = Optional.ofNullable(properties.getSessionTtl()).orElse(Duration.ofMinutes(10));
            long expireBefore = System.currentTimeMillis() - Math.max(1_000L, ttl.toMillis());
            sessions.forEach((sessionId, session) -> {
                if (session.lastAccessMillis() < expireBefore && sessions.remove(sessionId, session)) {
                    session.closeQuietly();
                    log.info("浏览器空闲会话已回收: sessionId={}", sessionId);
                }
            });
        }
        catch (RuntimeException exception) {
            log.warn("浏览器空闲会话清理失败", exception);
        }
    }

    private void evictExcessSessions(String currentSessionId) {
        int maxSessions = Math.max(1, properties.getMaxSessions());
        while (sessions.size() > maxSessions) {
            Optional<Map.Entry<String, BrowserSession>> oldest = sessions.entrySet().stream()
                .filter(entry -> !entry.getKey().equals(currentSessionId))
                .min(Map.Entry.comparingByValue((left, right) ->
                    Long.compare(left.lastAccessMillis(), right.lastAccessMillis())));
            if (oldest.isEmpty()) {
                return;
            }
            Map.Entry<String, BrowserSession> entry = oldest.get();
            if (sessions.remove(entry.getKey(), entry.getValue())) {
                entry.getValue().closeQuietly();
                log.info("浏览器会话数超过上限，已回收: sessionId={}, maxSessions={}", entry.getKey(), maxSessions);
            }
        }
    }

    private void assertPublicHost(String host) {
        try {
            InetAddress[] addresses = InetAddress.getAllByName(host);
            if (addresses.length == 0) {
                throw new IllegalArgumentException("目标主机无法解析: " + host);
            }
            for (InetAddress address : addresses) {
                if (isPrivateAddress(address)) {
                    throw new IllegalArgumentException("禁止访问内网或本机地址: " + host);
                }
            }
        }
        catch (UnknownHostException exception) {
            throw new IllegalArgumentException("目标主机无法解析: " + host, exception);
        }
    }

    private boolean isPrivateAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
            || address.isLoopbackAddress()
            || address.isLinkLocalAddress()
            || address.isSiteLocalAddress()
            || address.isMulticastAddress()) {
            return true;
        }
        byte[] bytes = address.getAddress();
        if (bytes.length == 4) {
            return isPrivateIpv4(bytes, 0);
        }
        if (bytes.length == 16) {
            // IPv4-mapped IPv6: ::ffff:127.0.0.1
            if (isIpv4Mapped(bytes)) {
                return isPrivateIpv4(bytes, 12);
            }
            // NAT64: 64:ff9b::/96
            if ((bytes[0] & 0xff) == 0x00
                && (bytes[1] & 0xff) == 0x64
                && (bytes[2] & 0xff) == 0xff
                && (bytes[3] & 0xff) == 0x9b) {
                return isPrivateIpv4(bytes, 12);
            }
            int first = bytes[0] & 0xff;
            // fc00::/7，IPv6 唯一本地地址。
            return (first & 0xfe) == 0xfc;
        }
        return false;
    }

    private boolean isIpv4Mapped(byte[] bytes) {
        for (int index = 0; index < 10; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return (bytes[10] & 0xff) == 0xff && (bytes[11] & 0xff) == 0xff;
    }

    private boolean isPrivateIpv4(byte[] bytes, int offset) {
        int first = bytes[offset] & 0xff;
        int second = bytes[offset + 1] & 0xff;
        int third = bytes[offset + 2] & 0xff;
        return first == 0
            || first == 10
            || first == 127
            || (first == 100 && second >= 64 && second <= 127)
            || (first == 169 && second == 254)
            || (first == 172 && second >= 16 && second <= 31)
            || (first == 192 && second == 168)
            || (first == 192 && second == 0 && third == 0)
            || (first == 198 && (second == 18 || second == 19))
            || first >= 224;
    }

    private boolean matchesHost(String host, String pattern) {
        if (StrUtil.isBlank(pattern)) {
            return false;
        }
        String normalized = pattern.trim().toLowerCase(Locale.ROOT);
        if (normalized.startsWith("*.")) {
            String suffix = normalized.substring(1);
            return host.endsWith(suffix) && host.length() > suffix.length();
        }
        return host.equals(normalized);
    }

    private String normalizeSessionId(String conversationId) {
        if (StrUtil.isBlank(conversationId)) {
            return DEFAULT_SESSION_ID;
        }
        String normalized = conversationId.trim();
        return normalized.length() <= 128 ? normalized : normalized.substring(0, 128);
    }

    private void ensureEnabled() {
        if (!properties.isEnabled()) {
            throw new IllegalStateException("浏览器工具当前已禁用");
        }
        if (closed) {
            throw new IllegalStateException("浏览器管理器已关闭");
        }
    }

    @Override
    @PreDestroy
    public void close() {
        closed = true;
        cleanupExecutor.shutdownNow();
        sessions.values().forEach(BrowserSession::closeQuietly);
        sessions.clear();
        if (browser != null) {
            try {
                browser.close();
            }
            catch (RuntimeException exception) {
                log.debug("关闭 Chromium 失败", exception);
            }
            browser = null;
        }
        closePlaywrightQuietly();
    }

    private void closePlaywrightQuietly() {
        if (playwright != null) {
            try {
                playwright.close();
            }
            catch (RuntimeException exception) {
                log.debug("关闭 Playwright 失败", exception);
            }
            playwright = null;
        }
    }

    private static final class BrowserSession {

        private final String sessionId;

        private final BrowserContext context;

        private final Page page;

        private final ReentrantLock lock = new ReentrantLock();

        private volatile long lastAccessMillis = System.currentTimeMillis();

        private BrowserSession(String sessionId, BrowserContext context, Page page) {
            this.sessionId = sessionId;
            this.context = context;
            this.page = page;
        }

        private <T> T execute(Function<Page, T> action) {
            lock.lock();
            try {
                if (page.isClosed()) {
                    throw new IllegalStateException("浏览器页面已关闭: " + sessionId);
                }
                lastAccessMillis = System.currentTimeMillis();
                return action.apply(page);
            }
            finally {
                lastAccessMillis = System.currentTimeMillis();
                lock.unlock();
            }
        }

        private boolean usable() {
            return !page.isClosed() && context != null;
        }

        private long lastAccessMillis() {
            return lastAccessMillis;
        }

        private void closeQuietly() {
            lock.lock();
            try {
                if (page != null && !page.isClosed()) {
                    page.close();
                }
            }
            catch (RuntimeException exception) {
                log.debug("关闭浏览器 Page 失败: sessionId={}", sessionId, exception);
            }
            finally {
                lock.unlock();
            }
            try {
                if (context != null) {
                    context.close();
                }
            }
            catch (RuntimeException exception) {
                log.debug("关闭浏览器 Context 失败: sessionId={}", sessionId, exception);
            }
        }
    }
}
