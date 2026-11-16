package org.javaup.ai.chatagent.tool.browser;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Playwright 浏览器工具配置。
 */
@ConfigurationProperties(prefix = "app.browser")
public class BrowserProperties {

    /**
     * 是否启用浏览器工具。
     */
    private boolean enabled = true;

    /**
     * 是否使用无头浏览器。
     */
    private boolean headless = true;

    /**
     * 容器环境中通常需要关闭 Chromium sandbox。
     */
    private boolean noSandbox = false;

    /**
     * 单次浏览器操作超时时间。
     */
    private int timeoutMs = 15_000;

    /**
     * 浏览器会话空闲回收时间。
     */
    private Duration sessionTtl = Duration.ofMinutes(10);

    /**
     * 空闲会话清理间隔，单位秒。
     */
    private int cleanupIntervalSeconds = 60;

    /**
     * 同时保留的最大浏览器会话数。
     */
    private int maxSessions = 20;

    /**
     * 返回给模型的最大页面文本长度。
     */
    private int maxSnapshotChars = 20_000;

    /**
     * 单次快照最多返回的可交互元素数量。
     */
    private int maxElements = 200;

    /**
     * 单轮对话最多打开多少次页面。
     */
    private int maxOpenCallsPerRun = 2;

    /**
     * 同一会话累计最多打开多少次页面。
     */
    private int maxOpenCallsPerThread = 8;

    /**
     * 单轮对话最多获取多少次页面快照。
     */
    private int maxSnapshotCallsPerRun = 6;

    /**
     * 同一会话累计最多获取多少次页面快照。
     */
    private int maxSnapshotCallsPerThread = 24;

    /**
     * 单轮对话最多执行多少次点击、按键和后退操作。
     */
    private int maxActionCallsPerRun = 6;

    /**
     * 同一会话累计最多执行多少次点击、按键和后退操作。
     */
    private int maxActionCallsPerThread = 24;

    /**
     * 单轮对话最多执行多少次表单输入。
     */
    private int maxTypeCallsPerRun = 3;

    /**
     * 同一会话累计最多执行多少次表单输入。
     */
    private int maxTypeCallsPerThread = 12;

    /**
     * 浏览器视口宽度。
     */
    private int viewportWidth = 1_280;

    /**
     * 浏览器视口高度。
     */
    private int viewportHeight = 720;

    /**
     * 浏览器语言。
     */
    private String locale = "zh-CN";

    /**
     * 自定义 User-Agent，留空时使用 Playwright 默认值。
     */
    private String userAgent = "";

    /**
     * 是否阻止访问回环、内网、链路本地和云元数据地址。
     */
    private boolean blockPrivateNetwork = true;

    /**
     * 允许访问的 URL 协议。
     */
    private List<String> allowedSchemes = new ArrayList<>(List.of("http", "https"));

    /**
     * 允许访问的主机白名单，支持 example.com 和 *.example.com；为空时不限制公网主机。
     */
    private List<String> allowedHosts = new ArrayList<>();

    /**
     * Chromium 启动参数。
     */
    private List<String> launchArgs = new ArrayList<>(List.of("--disable-dev-shm-usage", "--disable-gpu"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean isHeadless() {
        return headless;
    }

    public void setHeadless(boolean headless) {
        this.headless = headless;
    }

    public boolean isNoSandbox() {
        return noSandbox;
    }

    public void setNoSandbox(boolean noSandbox) {
        this.noSandbox = noSandbox;
    }

    public int getTimeoutMs() {
        return timeoutMs;
    }

    public void setTimeoutMs(int timeoutMs) {
        this.timeoutMs = timeoutMs;
    }

    public Duration getSessionTtl() {
        return sessionTtl;
    }

    public void setSessionTtl(Duration sessionTtl) {
        this.sessionTtl = sessionTtl;
    }

    public int getCleanupIntervalSeconds() {
        return cleanupIntervalSeconds;
    }

    public void setCleanupIntervalSeconds(int cleanupIntervalSeconds) {
        this.cleanupIntervalSeconds = cleanupIntervalSeconds;
    }

    public int getMaxSessions() {
        return maxSessions;
    }

    public void setMaxSessions(int maxSessions) {
        this.maxSessions = maxSessions;
    }

    public int getMaxSnapshotChars() {
        return maxSnapshotChars;
    }

    public void setMaxSnapshotChars(int maxSnapshotChars) {
        this.maxSnapshotChars = maxSnapshotChars;
    }

    public int getMaxElements() {
        return maxElements;
    }

    public void setMaxElements(int maxElements) {
        this.maxElements = maxElements;
    }

    public int getMaxOpenCallsPerRun() {
        return maxOpenCallsPerRun;
    }

    public void setMaxOpenCallsPerRun(int maxOpenCallsPerRun) {
        this.maxOpenCallsPerRun = maxOpenCallsPerRun;
    }

    public int getMaxOpenCallsPerThread() {
        return maxOpenCallsPerThread;
    }

    public void setMaxOpenCallsPerThread(int maxOpenCallsPerThread) {
        this.maxOpenCallsPerThread = maxOpenCallsPerThread;
    }

    public int getMaxSnapshotCallsPerRun() {
        return maxSnapshotCallsPerRun;
    }

    public void setMaxSnapshotCallsPerRun(int maxSnapshotCallsPerRun) {
        this.maxSnapshotCallsPerRun = maxSnapshotCallsPerRun;
    }

    public int getMaxSnapshotCallsPerThread() {
        return maxSnapshotCallsPerThread;
    }

    public void setMaxSnapshotCallsPerThread(int maxSnapshotCallsPerThread) {
        this.maxSnapshotCallsPerThread = maxSnapshotCallsPerThread;
    }

    public int getMaxActionCallsPerRun() {
        return maxActionCallsPerRun;
    }

    public void setMaxActionCallsPerRun(int maxActionCallsPerRun) {
        this.maxActionCallsPerRun = maxActionCallsPerRun;
    }

    public int getMaxActionCallsPerThread() {
        return maxActionCallsPerThread;
    }

    public void setMaxActionCallsPerThread(int maxActionCallsPerThread) {
        this.maxActionCallsPerThread = maxActionCallsPerThread;
    }

    public int getMaxTypeCallsPerRun() {
        return maxTypeCallsPerRun;
    }

    public void setMaxTypeCallsPerRun(int maxTypeCallsPerRun) {
        this.maxTypeCallsPerRun = maxTypeCallsPerRun;
    }

    public int getMaxTypeCallsPerThread() {
        return maxTypeCallsPerThread;
    }

    public void setMaxTypeCallsPerThread(int maxTypeCallsPerThread) {
        this.maxTypeCallsPerThread = maxTypeCallsPerThread;
    }

    public int getViewportWidth() {
        return viewportWidth;
    }

    public void setViewportWidth(int viewportWidth) {
        this.viewportWidth = viewportWidth;
    }

    public int getViewportHeight() {
        return viewportHeight;
    }

    public void setViewportHeight(int viewportHeight) {
        this.viewportHeight = viewportHeight;
    }

    public String getLocale() {
        return locale;
    }

    public void setLocale(String locale) {
        this.locale = locale;
    }

    public String getUserAgent() {
        return userAgent;
    }

    public void setUserAgent(String userAgent) {
        this.userAgent = userAgent;
    }

    public boolean isBlockPrivateNetwork() {
        return blockPrivateNetwork;
    }

    public void setBlockPrivateNetwork(boolean blockPrivateNetwork) {
        this.blockPrivateNetwork = blockPrivateNetwork;
    }

    public List<String> getAllowedSchemes() {
        return allowedSchemes;
    }

    public void setAllowedSchemes(List<String> allowedSchemes) {
        this.allowedSchemes = allowedSchemes;
    }

    public List<String> getAllowedHosts() {
        return allowedHosts;
    }

    public void setAllowedHosts(List<String> allowedHosts) {
        this.allowedHosts = allowedHosts;
    }

    public List<String> getLaunchArgs() {
        return launchArgs;
    }

    public void setLaunchArgs(List<String> launchArgs) {
        this.launchArgs = launchArgs;
    }
}
