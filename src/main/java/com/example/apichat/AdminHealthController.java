package com.example.apichat;

import com.example.apichat.service.RagService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.sql.Connection;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import javax.sql.DataSource;

@RestController
@RequestMapping("/admin")
public class AdminHealthController {

    private final DataSource dataSource;
    private final RagService ragService;

    @Value("${spring.ai.dashscope.agent.app-id:}")
    private String appId;

    @Value("${spring.ai.dashscope.api-key:}")
    private String apiKey;

    public AdminHealthController(DataSource dataSource, RagService ragService) {
        this.dataSource = dataSource;
        this.ragService = ragService;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("timestamp", String.valueOf(LocalDateTime.now()));

        Map<String, Object> db = new LinkedHashMap<>();
        try (Connection connection = dataSource.getConnection()) {
            db.put("ok", connection.isValid(2));
            db.put("url", connection.getMetaData().getURL());
            db.put("product", connection.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            db.put("ok", false);
            db.put("error", e.getMessage());
        }
        result.put("database", db);

        Map<String, Object> rag = new LinkedHashMap<>();
        rag.put("documents", ragService.documentCount());
        rag.put("chunks", ragService.chunkCount());
        rag.put("lastIndexedAt", String.valueOf(ragService.lastIndexedAt()));
        result.put("rag", rag);

        Map<String, Object> model = new LinkedHashMap<>();
        model.put("appIdConfigured", appId != null && !appId.isBlank());
        model.put("apiKeyConfigured", apiKey != null && !apiKey.isBlank());
        model.put("provider", "dashscope-agent");
        result.put("model", model);

        return result;
    }
}
