package com.example.apichat.rag;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

public class VectorStore {

    private final List<DocChunk> store = new ArrayList<>();

    public void add(DocChunk chunk) {
        store.add(chunk);
    }

    public List<DocChunk> topK(float[] query, int k) {
        return store.stream()
                .sorted(Comparator.comparingDouble(
                        c -> -cosine(c.getEmbedding(), query)
                ))
                .limit(k)
                .toList();
    }

    private double cosine(float[] a, float[] b) {
        double dot = 0, na = 0, nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return dot / (Math.sqrt(na) * Math.sqrt(nb) + 1e-9);
    }
}