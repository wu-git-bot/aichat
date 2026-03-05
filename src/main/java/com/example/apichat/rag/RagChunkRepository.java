package com.example.apichat.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface RagChunkRepository extends JpaRepository<RagChunkEntity, Long> {
    List<RagChunkEntity> findAllByOrderByIdAsc();
    List<RagChunkEntity> findAllByDocumentIdOrderByChunkIndexAsc(Long documentId);
    long countByDocumentId(Long documentId);
    void deleteByDocumentId(Long documentId);
}
