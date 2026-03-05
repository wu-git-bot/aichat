package com.example.apichat;

import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgent;
import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgentOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeAgentApi;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

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
                        .withSessionId("session-001")
                        .withIncrementalOutput(true)
                        .withHasThoughts(false)
                        .build()
        );
    }

    @GetMapping(value = "/bailian/agent/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "message", defaultValue = "你好") String message) {
        SseEmitter emitter = new SseEmitter(0L);

        try {
            Prompt prompt = new Prompt(message, DashScopeAgentOptions.builder().withAppId(appId).build());
            agent.stream(prompt).subscribe(
                    response -> {
                        try {
                            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                                logger.warn("empty stream response from model");
                                return;
                            }

                            String content = response.getResult().getOutput().getText();
                            if (content == null || content.trim().isEmpty()) {
                                return;
                            }

                            emitter.send(SseEmitter.event().name("chunk").data(content));
                        } catch (Exception e) {
                            logger.error("failed to send sse chunk", e);
                            sendModelError(emitter, "stream output failed: " + e.getMessage());
                        }
                    },
                    error -> {
                        logger.error("agent stream failed", error);
                        sendModelError(emitter, "model call failed: " + error.getClass().getSimpleName() + " - " + error.getMessage());
                    },
                    () -> {
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (Exception ignored) {
                        }
                        emitter.complete();
                    }
            );
        } catch (Exception e) {
            logger.error("failed to start stream", e);
            sendModelError(emitter, "model start failed: " + e.getClass().getSimpleName() + " - " + e.getMessage());
        }

        return emitter;
    }

    private void sendModelError(SseEmitter emitter, String msg) {
        try {
            emitter.send(SseEmitter.event().name("model_error").data(msg));
        } catch (Exception ignored) {
        }
        emitter.complete();
    }
}
