package com.example.apichat.rag;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface RagDocumentRepository extends JpaRepository<RagDocumentEntity, Long> {
    Optional<RagDocumentEntity> findByFilename(String filename);
}

