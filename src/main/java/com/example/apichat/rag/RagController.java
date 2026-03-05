package com.example.apichat.rag;

import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgent;
import com.alibaba.cloud.ai.dashscope.agent.DashScopeAgentOptions;
import com.alibaba.cloud.ai.dashscope.api.DashScopeAgentApi;
import com.example.apichat.service.RagService;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.util.Map;

@RestController
@RequestMapping("/rag")
public class RagController {

    private static final int RAG_TOP_K = 5;

    private final RagService ragService;
    private final ChatClient chatClient;
    private final DashScopeAgent agent;

    @Value("${spring.ai.dashscope.agent.app-id}")
    private String appId;

    public RagController(RagService ragService, ChatClient.Builder builder, DashScopeAgentApi dashscopeAgentApi) {
        this.ragService = ragService;
        this.chatClient = builder.build();
        this.agent = new DashScopeAgent(
                dashscopeAgentApi,
                DashScopeAgentOptions.builder()
                        .withSessionId("rag-session")
                        .withIncrementalOutput(true)
                        .withHasThoughts(false)
                        .build()
        );
    }

    @PostMapping("/upload")
    public String upload(@RequestParam("file") MultipartFile file) throws Exception {
        long chunks = ragService.saveFile(file);
        return "OK: " + file.getOriginalFilename() + " (chunks=" + chunks + ")";
    }

    @GetMapping("/status")
    public Map<String, Object> status() {
        return Map.of(
                "chunks", ragService.chunkCount(),
                "documents", ragService.documentCount(),
                "lastIndexedAt", String.valueOf(ragService.lastIndexedAt())
        );
    }

    @GetMapping("/documents")
    public Object listDocuments() {
        return ragService.listDocuments();
    }

    @DeleteMapping("/documents/{id}")
    public Map<String, Object> deleteDocument(@PathVariable Long id) {
        ragService.deleteDocument(id);
        return Map.of("ok", true);
    }

    @PostMapping("/documents/{id}/reindex")
    public Map<String, Object> reindexDocument(@PathVariable Long id) {
        ragService.rebuildDocument(id);
        return Map.of("ok", true);
    }

    @PostMapping("/chat")
    public String chat(@RequestBody Map<String, String> body) {
        String prompt = body.get("prompt");
        if (prompt == null || prompt.isBlank()) {
            return "请输入问题";
        }

        String context = ragService.searchTopK(prompt, RAG_TOP_K);
        if (context.isEmpty()) {
            return "当前没有可用知识库文档，请先上传文档。";
        }

        String systemPrompt = "请严格基于参考资料回答。如果资料没有提到，就明确说不知道并提示补充资料。";
        String userPrompt = "参考资料:\n" + context + "\n\n用户问题:\n" + prompt;
        return chatClient.prompt().system(systemPrompt).user(userPrompt).call().content();
    }

    @GetMapping(value = "/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter stream(@RequestParam(value = "message", defaultValue = "") String message) {
        SseEmitter emitter = new SseEmitter(0L);
        if (message == null || message.isBlank()) {
            sendModelError(emitter, "empty message");
            return emitter;
        }

        String context = ragService.searchTopK(message, RAG_TOP_K);
        String fullPrompt = context.isEmpty()
                ? "当前未上传知识库文档。请提示用户先上传文档再提问。用户问题: " + message
                : "请严格基于以下资料回答，不确定就明确说明。\n\n参考资料:\n" + context + "\n\n用户问题: " + message;

        try {
            Prompt prompt = new Prompt(fullPrompt, DashScopeAgentOptions.builder().withAppId(appId).build());
            agent.stream(prompt).subscribe(
                    response -> {
                        try {
                            if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
                                return;
                            }
                            String content = response.getResult().getOutput().getText();
                            if (content == null || content.trim().isEmpty()) {
                                return;
                            }
                            emitter.send(SseEmitter.event().name("chunk").data(content));
                        } catch (Exception e) {
                            sendModelError(emitter, "stream output failed: " + e.getMessage());
                        }
                    },
                    error -> sendModelError(emitter, "model call failed: " + error.getClass().getSimpleName() + " - " + error.getMessage()),
                    () -> {
                        try {
                            emitter.send(SseEmitter.event().name("done").data("[DONE]"));
                        } catch (Exception ignored) {
                        }
                        emitter.complete();
                    }
            );
        } catch (Exception e) {
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
