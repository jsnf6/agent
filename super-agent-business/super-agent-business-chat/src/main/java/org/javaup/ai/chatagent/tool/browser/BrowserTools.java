package org.javaup.ai.chatagent.tool.browser;

import cn.hutool.core.util.StrUtil;
import com.alibaba.cloud.ai.graph.RunnableConfig;
import com.alibaba.cloud.ai.graph.agent.tools.ToolContextHelper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.options.WaitUntilState;
import lombok.extern.slf4j.Slf4j;
import org.javaup.ai.chatagent.support.ChatContextKeys;
import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * 暴露给 Spring AI Agent 的浏览器工具。
 */
@Slf4j
@Component
@ConditionalOnProperty(prefix = "app.browser", name = "enabled", havingValue = "true", matchIfMissing = true)
public class BrowserTools {

    private static final String SNAPSHOT_SCRIPT = """
        (args) => {
          const maxText = Math.max(1, Number(args.maxText) || 20000);
          const maxElements = Math.max(1, Number(args.maxElements) || 200);
          const isVisible = (element) => {
            const style = window.getComputedStyle(element);
            const rect = element.getBoundingClientRect();
            return style.display !== 'none'
              && style.visibility !== 'hidden'
              && Number(style.opacity) !== 0
              && rect.width > 0
              && rect.height > 0;
          };
          const textOf = (element) => {
            const value = element.getAttribute('aria-label')
              || element.getAttribute('title')
              || element.getAttribute('placeholder')
              || element.innerText
              || element.value
              || '';
            return String(value).replace(/\\s+/g, ' ').trim().slice(0, 160);
          };
          document.querySelectorAll('[data-agent-ref]').forEach((element) => {
            element.removeAttribute('data-agent-ref');
          });
          const elements = [];
          const selector = 'a,button,input,textarea,select,[role="button"],[role="link"],[role="textbox"],[contenteditable="true"]';
          for (const element of document.querySelectorAll(selector)) {
            if (!isVisible(element) || elements.length >= maxElements) {
              continue;
            }
            const ref = 'e' + (elements.length + 1);
            element.setAttribute('data-agent-ref', ref);
            const tag = element.tagName.toLowerCase();
            const role = element.getAttribute('role') || tag;
            const item = {
              ref,
              tag,
              role,
              name: textOf(element),
              type: element.getAttribute('type') || '',
              disabled: Boolean(element.disabled)
            };
            if (tag === 'a' && element.href) {
              item.href = String(element.href).slice(0, 500);
            }
            elements.push(item);
          }
          const fullText = (document.body && document.body.innerText ? document.body.innerText : '')
            .replace(/\\n{3,}/g, '\\n\\n')
            .trim();
          return {
            title: document.title || '',
            url: location.href,
            text: fullText.slice(0, maxText),
            textTruncated: fullText.length > maxText,
            elementCount: elements.length,
            elements
          };
        }
        """;

    private final BrowserProperties properties;

    private final BrowserSessionManager sessionManager;

    private final ObjectMapper objectMapper;

    public BrowserTools(BrowserProperties properties,
                        BrowserSessionManager sessionManager,
                        ObjectMapper objectMapper) {
        this.properties = properties;
        this.sessionManager = sessionManager;
        this.objectMapper = objectMapper;
    }

    @Tool(name = "browser_open", description = "打开一个 http/https 网页，返回页面标题、正文和可交互元素快照。需要用户提供完整 URL，或先通过搜索工具得到 URL。")
    public String browserOpen(
            @ToolParam(description = "完整网页地址，必须以 http:// 或 https:// 开头")
            String url,
            ToolContext toolContext) {
        String safeUrl = sessionManager.validateUrl(url);
        return execute(resolveSessionId(toolContext), "browser_open", page -> {
            page.navigate(safeUrl, new Page.NavigateOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(properties.getTimeoutMs()));
            page.waitForTimeout(500);
            return snapshotJson(page, resolveSessionId(toolContext));
        });
    }

    @Tool(name = "browser_snapshot", description = "重新读取当前网页，返回最新页面文本和可交互元素引用。页面跳转、点击或输入后应重新调用。")
    public String browserSnapshot(ToolContext toolContext) {
        String sessionId = resolveSessionId(toolContext);
        return execute(sessionId, "browser_snapshot", page -> snapshotJson(page, sessionId));
    }

    @Tool(name = "browser_click", description = "点击当前页面快照中带有 ref 的元素。点击后会自动返回最新页面快照。")
    public String browserClick(
            @ToolParam(description = "browser_snapshot 返回的元素引用，例如 e3")
            String ref,
            ToolContext toolContext) {
        assertElementRef(ref);
        String sessionId = resolveSessionId(toolContext);
        return execute(sessionId, "browser_click", page -> {
            Locator locator = page.locator("[data-agent-ref='" + ref + "']");
            if (locator.count() == 0) {
                throw new IllegalArgumentException("页面中不存在元素引用: " + ref + "，请重新获取页面快照");
            }
            locator.click(new Locator.ClickOptions().setTimeout(properties.getTimeoutMs()));
            page.waitForTimeout(800);
            return snapshotJson(page, sessionId);
        });
    }

    @Tool(name = "browser_type", description = "向当前页面快照中带有 ref 的输入框填写文本；submit=true 时填写后按 Enter。完成后返回最新页面快照。")
    public String browserType(
            @ToolParam(description = "browser_snapshot 返回的输入元素引用，例如 e1")
            String ref,
            @ToolParam(description = "要填写的文本")
            String text,
            @ToolParam(description = "是否填写后提交，默认 false", required = false)
            Boolean submit,
            ToolContext toolContext) {
        assertElementRef(ref);
        String sessionId = resolveSessionId(toolContext);
        return execute(sessionId, "browser_type", page -> {
            Locator locator = page.locator("[data-agent-ref='" + ref + "']");
            if (locator.count() == 0) {
                throw new IllegalArgumentException("页面中不存在元素引用: " + ref + "，请重新获取页面快照");
            }
            locator.fill(StrUtil.blankToDefault(text, ""), new Locator.FillOptions()
                .setTimeout(properties.getTimeoutMs()));
            if (Boolean.TRUE.equals(submit)) {
                page.keyboard().press("Enter");
                page.waitForTimeout(800);
            }
            return snapshotJson(page, sessionId);
        });
    }

    @Tool(name = "browser_press", description = "在当前页面按下键盘按键，例如 Enter、Escape、PageDown、Control+A。")
    public String browserPress(
            @ToolParam(description = "Playwright 按键名称，例如 Enter、Escape、PageDown、Control+A")
            String key,
            ToolContext toolContext) {
        if (StrUtil.isBlank(key) || !key.matches("[A-Za-z0-9+_-]+")) {
            throw new IllegalArgumentException("非法按键名称");
        }
        String sessionId = resolveSessionId(toolContext);
        return execute(sessionId, "browser_press", page -> {
            page.keyboard().press(key);
            page.waitForTimeout(300);
            return snapshotJson(page, sessionId);
        });
    }

    @Tool(name = "browser_back", description = "返回浏览器上一页，并返回最新页面快照。")
    public String browserBack(ToolContext toolContext) {
        String sessionId = resolveSessionId(toolContext);
        return execute(sessionId, "browser_back", page -> {
            page.goBack(new Page.GoBackOptions()
                .setWaitUntil(WaitUntilState.DOMCONTENTLOADED)
                .setTimeout(properties.getTimeoutMs()));
            page.waitForTimeout(500);
            return snapshotJson(page, sessionId);
        });
    }

    @Tool(name = "browser_close", description = "关闭当前会话的浏览器页面，释放浏览器资源。")
    public String browserClose(ToolContext toolContext) {
        String sessionId = resolveSessionId(toolContext);
        sessionManager.closeSession(sessionId);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sessionId", sessionId);
        result.put("closed", true);
        return writeJson(result);
    }

    private String snapshotJson(Page page, String sessionId) {
        Map<String, Object> options = Map.of(
            "maxText", Math.max(1, properties.getMaxSnapshotChars()),
            "maxElements", Math.max(1, properties.getMaxElements())
        );
        Object raw = page.evaluate(SNAPSHOT_SCRIPT, options);
        Map<String, Object> snapshot = new LinkedHashMap<>();
        if (raw instanceof Map<?, ?> map) {
            map.forEach((key, value) -> {
                if (key != null) {
                    snapshot.put(String.valueOf(key), value);
                }
            });
        }
        snapshot.put("sessionId", sessionId);
        return writeJson(snapshot);
    }

    private String execute(String sessionId, String action, java.util.function.Function<Page, String> callback) {
        long startTime = System.currentTimeMillis();
        try {
            String result = sessionManager.execute(sessionId, callback);
            log.info("浏览器工具执行完成: action={}, sessionId={}, durationMs={}",
                action, sessionId, Math.max(0L, System.currentTimeMillis() - startTime));
            return result;
        }
        catch (IllegalArgumentException exception) {
            throw exception;
        }
        catch (RuntimeException exception) {
            log.warn("浏览器工具执行失败: action={}, sessionId={}, durationMs={}",
                action, sessionId, Math.max(0L, System.currentTimeMillis() - startTime), exception);
            throw new IllegalStateException(action + " 执行失败: " + StrUtil.blankToDefault(exception.getMessage(), "未知错误"), exception);
        }
    }

    private String resolveSessionId(ToolContext toolContext) {
        if (toolContext == null) {
            return "anonymous";
        }
        RunnableConfig config = ToolContextHelper.getConfig(toolContext).orElse(null);
        if (config != null) {
            Optional<String> threadId = config.threadId();
            if (threadId.isPresent() && StrUtil.isNotBlank(threadId.get())) {
                return threadId.get().trim();
            }
        }
        Object value = toolContext.getContext().get(ChatContextKeys.CONVERSATION_ID);
        if (value instanceof String text && StrUtil.isNotBlank(text)) {
            return text.trim();
        }
        return "anonymous";
    }

    private void assertElementRef(String ref) {
        if (StrUtil.isBlank(ref) || !ref.matches("e\\d+")) {
            throw new IllegalArgumentException("非法元素引用，必须使用页面快照返回的 ref");
        }
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        }
        catch (JsonProcessingException exception) {
            throw new IllegalStateException("浏览器结果序列化失败", exception);
        }
    }
}