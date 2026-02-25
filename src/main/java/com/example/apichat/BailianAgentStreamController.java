package com.example.apichat;

import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgent;
import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgentOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeAgentApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/ai")
public class BailianAgentStreamController {

    private static final Logger logger = LoggerFactory.getLogger(BailianAgentStreamController.class);

    private final DashScopeAgent agent;

    @Value("${spring.ai.dashscope.agent.app-id}")
    private String appId;

    public BailianAgentStreamController(DashScopeAgentApi dashscopeAgentApi) {
        this.agent = new DashScopeAgent(
                dashscopeAgentApi,
                DashScopeAgentOptions.builder()
                        .withSessionId("session-001") // 可替换为用户ID
                        .withIncrementalOutput(true)
                        .withHasThoughts(false)
                        .build()
        );
    }

    @GetMapping(value = "/bailian/agent/stream", produces = "text/event-stream;charset=UTF-8")
    public Flux<ServerSentEvent<String>> stream(@RequestParam(value = "message", defaultValue = "你好") String message) {
        Prompt prompt = new Prompt(message, DashScopeAgentOptions.builder().withAppId(appId).build());

        return agent.stream(prompt)
                .flatMap(response -> {
                    if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                        logger.warn("空响应，跳过。");
                        return Flux.empty();
                    }

                    String content = response.getResult().getOutput().getText();
                    if (content == null || content.trim().isEmpty()) {
                        return Flux.empty();
                    }

                    return Flux.just(ServerSentEvent.<String>builder()
                            .data(content.trim())
                            .build());
                });
    }

}
