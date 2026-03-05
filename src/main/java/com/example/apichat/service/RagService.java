package com.example.apichat.service;

import com.example.apichat.rag.RagChunkEntity;
import com.example.apichat.rag.RagChunkRepository;
import com.example.apichat.rag.RagDocumentEntity;
import com.example.apichat.rag.RagDocumentRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class RagService {

    private static final Logger log = LoggerFactory.getLogger(RagService.class);

    private final EmbeddingModel embeddingModel;
    private final RagChunkRepository ragChunkRepository;
    private final RagDocumentRepository ragDocumentRepository;

    private final List<float[]> vectors = new ArrayList<>();
    private final List<String> texts = new ArrayList<>();

    public RagService(EmbeddingModel embeddingModel,
                      RagChunkRepository ragChunkRepository,
                      RagDocumentRepository ragDocumentRepository) {
        this.embeddingModel = embeddingModel;
        this.ragChunkRepository = ragChunkRepository;
        this.ragDocumentRepository = ragDocumentRepository;
    }

    @PostConstruct
    public synchronized void loadFromDatabase() {
        vectors.clear();
        texts.clear();

        List<RagChunkEntity> all = ragChunkRepository.findAllByOrderByIdAsc();
        for (RagChunkEntity entity : all) {
            try {
                vectors.add(deserializeVector(entity.getEmbedding()));
                texts.add(entity.getContent());
            } catch (Exception ex) {
                log.warn("skip invalid rag chunk id={}, error={}", entity.getId(), ex.getMessage());
            }
        }
        log.info("RAG cache loaded from database, chunks={}", vectors.size());
    }

    @Transactional
    public synchronized long saveFile(MultipartFile file) throws Exception {
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("uploaded.txt").trim();
        if (filename.isEmpty()) {
            filename = "uploaded.txt";
        }

        String text = new String(file.getBytes(), StandardCharsets.UTF_8);

        RagDocumentEntity document = ragDocumentRepository.findByFilename(filename).orElseGet(RagDocumentEntity::new);
        document.setFilename(filename);
        document.setContent(text);
        document.setUpdatedAt(LocalDateTime.now());
        document = ragDocumentRepository.save(document);

        rebuildDocumentChunks(document.getId(), text);
        loadFromDatabase();
        return ragChunkRepository.countByDocumentId(document.getId());
    }

    @Transactional
    public synchronized void rebuildDocument(Long documentId) {
        RagDocumentEntity document = ragDocumentRepository.findById(documentId)
                .orElseThrow(() -> new IllegalArgumentException("document not found: " + documentId));
        document.setUpdatedAt(LocalDateTime.now());
        ragDocumentRepository.save(document);

        rebuildDocumentChunks(documentId, document.getContent());
        loadFromDatabase();
    }

    @Transactional
    public synchronized void deleteDocument(Long documentId) {
        ragChunkRepository.deleteByDocumentId(documentId);
        ragDocumentRepository.deleteById(documentId);
        loadFromDatabase();
    }

    public synchronized List<Map<String, Object>> listDocuments() {
        List<RagDocumentEntity> docs = ragDocumentRepository.findAll();
        docs.sort(Comparator.comparing(RagDocumentEntity::getUpdatedAt).reversed());

        List<Map<String, Object>> result = new ArrayList<>();
        for (RagDocumentEntity doc : docs) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", doc.getId());
            item.put("filename", doc.getFilename());
            item.put("updatedAt", doc.getUpdatedAt());
            item.put("chunks", ragChunkRepository.countByDocumentId(doc.getId()));
            result.add(item);
        }
        return result;
    }

    public synchronized String searchTopK(String query, int k) {
        if (vectors.isEmpty() || k <= 0) {
            return "";
        }

        float[] q = embed(query);
        int n = Math.min(k, vectors.size());

        List<Integer> indices = new ArrayList<>(vectors.size());
        for (int i = 0; i < vectors.size(); i++) {
            indices.add(i);
        }

        indices.sort((a, b) -> Double.compare(
                cosine(q, vectors.get(b)),
                cosine(q, vectors.get(a))
        ));

        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < n; i++) {
            if (i > 0) sb.append("\n\n");
            sb.append(texts.get(indices.get(i)));
        }
        return sb.toString();
    }

    public synchronized int chunkCount() {
        return vectors.size();
    }

    public synchronized int documentCount() {
        return (int) ragDocumentRepository.count();
    }

    public synchronized LocalDateTime lastIndexedAt() {
        return ragDocumentRepository.findAll().stream()
                .map(RagDocumentEntity::getUpdatedAt)
                .filter(v -> v != null)
                .max(LocalDateTime::compareTo)
                .orElse(null);
    }

    private void rebuildDocumentChunks(Long documentId, String text) {
        List<String> chunks = splitText(text, 300);
        ragChunkRepository.deleteByDocumentId(documentId);

        List<RagChunkEntity> toSave = new ArrayList<>();
        int idx = 0;
        for (String chunk : chunks) {
            if (chunk == null || chunk.isBlank()) {
                continue;
            }

            float[] vector = embeddingModel.embed(chunk);
            RagChunkEntity entity = new RagChunkEntity();
            entity.setDocumentId(documentId);
            entity.setChunkIndex(idx++);
            entity.setContent(chunk);
            entity.setEmbedding(serializeVector(vector));
            toSave.add(entity);
        }
        if (!toSave.isEmpty()) {
            ragChunkRepository.saveAll(toSave);
        }
    }

    private float[] embed(String text) {
        return embeddingModel.embed(text);
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    private List<String> splitText(String text, int size) {
        List<String> result = new ArrayList<>();
        for (int i = 0; i < text.length(); i += size) {
            result.add(text.substring(i, Math.min(i + size, text.length())));
        }
        return result;
    }

    private String serializeVector(float[] vector) {
        StringBuilder sb = new StringBuilder(vector.length * 8);
        for (int i = 0; i < vector.length; i++) {
            if (i > 0) sb.append(',');
            sb.append(vector[i]);
        }
        return sb.toString();
    }

    private float[] deserializeVector(String value) {
        String[] parts = value.split(",");
        float[] vector = new float[parts.length];
        for (int i = 0; i < parts.length; i++) {
            vector[i] = Float.parseFloat(parts[i]);
        }
        return vector;
    }
}
