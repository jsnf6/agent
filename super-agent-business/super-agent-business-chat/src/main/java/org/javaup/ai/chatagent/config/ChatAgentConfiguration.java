package org.javaup.ai.chatagent.config;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import javax.sql.DataSource;

import com.alibaba.cloud.ai.graph.agent.ReactAgent;
import com.alibaba.cloud.ai.graph.agent.hook.Hook;
import com.alibaba.cloud.ai.graph.agent.hook.modelcalllimit.ModelCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.hook.toolcalllimit.ToolCallLimitHook;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolerror.ToolErrorInterceptor;
import com.alibaba.cloud.ai.graph.agent.interceptor.toolretry.ToolRetryInterceptor;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.CreateOption;
import com.alibaba.cloud.ai.graph.checkpoint.savers.mysql.MysqlSaver;
import org.javaup.ai.chatagent.support.DashScopeCompatibilityInterceptor;
import org.javaup.ai.chatagent.support.TavilyToolInputFallbackInterceptor;
import org.javaup.ai.chatagent.tool.TavilySearchRequest;
import org.javaup.ai.chatagent.tool.TavilySearchTool;
import org.javaup.ai.chatagent.tool.browser.BrowserProperties;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.ToolCallbackProvider;
import org.springframework.ai.tool.function.FunctionToolCallback;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @program:
 * @description: 配置类
 * @author:
 **/
@Configuration
@EnableConfigurationProperties({ChatAgentProperties.class, TavilySearchProperties.class, BrowserProperties.class})
public class ChatAgentConfiguration {

    @Bean
    public MysqlSaver mysqlCheckpointSaver(DataSource dataSource) {

        return MysqlSaver.builder()
            .dataSource(dataSource)
            .createOption(CreateOption.CREATE_IF_NOT_EXISTS)
            .build();
    }

    @Bean
    public ToolCallback tavilySearchToolCallback(TavilySearchTool tavilySearchTool) {

        return FunctionToolCallback
            .builder("tavily_search", tavilySearchTool::search)
            .description("联网搜索最新信息、事实资料和网页来源。调用时必须传 JSON 参数，且至少包含非空 query；可选 topic 和 maxResults，其中 topic 仅允许 general、news、finance。")
            .inputType(TavilySearchRequest.class)
            .build();
    }

    @Bean
    public ReactAgent businessChatReactAgent(ChatModel chatModel,
                                             MysqlSaver mysqlCheckpointSaver,
                                             ToolCallback tavilySearchToolCallback,
                                             ObjectProvider<ToolCallbackProvider> browserToolCallbackProviders,
                                             BrowserProperties browserProperties,
                                             ChatAgentProperties chatAgentProperties,
                                             DashScopeCompatibilityInterceptor dashScopeCompatibilityInterceptor,
                                             TavilyToolInputFallbackInterceptor tavilyToolInputFallbackInterceptor) {
        ToolCallbackProvider browserToolCallbackProvider = browserToolCallbackProviders.orderedStream()
            .findFirst()
            .orElse(null);
        ToolCallback[] browserToolCallbacks = browserToolCallbackProvider == null
            ? new ToolCallback[0]
            : browserToolCallbackProvider.getToolCallbacks();

        List<Hook> hooks = new ArrayList<>();
        hooks.add(ModelCallLimitHook.builder()
            .runLimit(chatAgentProperties.getMaxModelCallsPerRun())
            .threadLimit(chatAgentProperties.getMaxModelCallsPerThread())
            .exitBehavior(ModelCallLimitHook.ExitBehavior.END)
            .build());
        hooks.add(ToolCallLimitHook.builder()
            .toolName("tavily_search")
            .runLimit(chatAgentProperties.getMaxToolCallsPerRun())
            .threadLimit(chatAgentProperties.getMaxToolCallsPerThread())
            .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
            .build());
        if (browserToolCallbackProvider != null) {
            hooks.add(browserToolCallLimit("browser_open",
                browserProperties.getMaxOpenCallsPerRun(),
                browserProperties.getMaxOpenCallsPerThread()));
            hooks.add(browserToolCallLimit("browser_snapshot",
                browserProperties.getMaxSnapshotCallsPerRun(),
                browserProperties.getMaxSnapshotCallsPerThread()));
            hooks.add(browserToolCallLimit("browser_click",
                browserProperties.getMaxActionCallsPerRun(),
                browserProperties.getMaxActionCallsPerThread()));
            hooks.add(browserToolCallLimit("browser_type",
                browserProperties.getMaxTypeCallsPerRun(),
                browserProperties.getMaxTypeCallsPerThread()));
            hooks.add(browserToolCallLimit("browser_press",
                browserProperties.getMaxActionCallsPerRun(),
                browserProperties.getMaxActionCallsPerThread()));
            hooks.add(browserToolCallLimit("browser_back",
                browserProperties.getMaxActionCallsPerRun(),
                browserProperties.getMaxActionCallsPerThread()));
        }

        return ReactAgent.builder()

            .name("business_chat_agent")
            .model(chatModel)
            .instruction(chatAgentProperties.getSystemPrompt())

            .tools(Stream.concat(
                    Stream.of(tavilySearchToolCallback),
                    Arrays.stream(browserToolCallbacks))
                .toArray(ToolCallback[]::new))
            .saver(mysqlCheckpointSaver)

            .parallelToolExecution(true)
            .maxParallelTools(4)

            .hooks(hooks)

            .interceptors(
                dashScopeCompatibilityInterceptor,
                tavilyToolInputFallbackInterceptor,
                ToolRetryInterceptor.builder()
                    .toolName("tavily_search")
                    .maxRetries(2)
                    .initialDelay(200L)
                    .maxDelay(1200L)
                    .jitter(true)
                    .onFailure(ToolRetryInterceptor.OnFailureBehavior.RETURN_MESSAGE)
                    .build(),
                ToolErrorInterceptor.builder().build()
            )
            .build();
    }

    private ToolCallLimitHook browserToolCallLimit(String toolName, int runLimit, int threadLimit) {
        int normalizedRunLimit = Math.max(1, runLimit);
        int normalizedThreadLimit = Math.max(normalizedRunLimit, threadLimit);
        return ToolCallLimitHook.builder()
            .toolName(toolName)
            .runLimit(normalizedRunLimit)
            .threadLimit(normalizedThreadLimit)
            .exitBehavior(ToolCallLimitHook.ExitBehavior.END)
            .build();
    }
}
