package com.lifos.backend.service;

import com.lifos.backend.dto.*;
import com.lifos.backend.entity.Note;
import com.lifos.backend.entity.User;
import com.lifos.backend.event.KnowledgeGraphTriggerEvent;
import com.lifos.backend.exception.ResourceNotFoundException;
import com.lifos.backend.repository.NoteRepository;
import com.lifos.backend.repository.UserRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Slf4j
@Service
@RequiredArgsConstructor
public class NoteService {

    private final NoteRepository noteRepository;
    private final UserRepository userRepository;
    private final ApplicationEventPublisher eventPublisher;
    private final ActivityLogService activityLogService;
    private final IngestionPipeline ingestionPipeline;

    private User getUser(String uid) {
        return userRepository.findById(uid)
                .orElseThrow(() -> new ResourceNotFoundException("User", "uid", uid));
    }

    private NoteResponse toResponse(Note n) {
        return NoteResponse.builder()
                .id(n.getId().toString())
                .title(n.getTitle())
                .content(n.getContent())
                .type(n.getType())
                .createdAt(n.getCreatedAt().toString())
                .updatedAt(n.getUpdatedAt() != null ? n.getUpdatedAt().toString() : null)
                .build();
    }

    public List<NoteResponse> getAll(String uid) {
        return noteRepository.findAllByUserUidOrderByCreatedAtDesc(uid)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public NoteResponse create(String uid, CreateNoteRequest req) {
        User user = getUser(uid);
        log.info("Creating note for user [{}]: '{}'", uid, req.getTitle());
        Note n = Note.builder()
                .user(user)
                .title(req.getTitle())
                .content(req.getContent())
                .type(req.getType())
                .build();
        Note saved = noteRepository.save(n);
        String embedText = buildEmbedText(saved);
        ingestionPipeline.ingestChunked(uid, "note", saved.getId(), embedText);
        eventPublisher.publishEvent(new KnowledgeGraphTriggerEvent(
                uid, "note", saved.getId(), embedText));
        activityLogService.log(uid, "notes", "created", saved.getId(), "Created note: " + saved.getTitle());
        return toResponse(saved);
    }

    @Transactional
    public NoteResponse update(String uid, UUID id, UpdateNoteRequest req) {
        log.debug("Updating note [{}] for user [{}]", id, uid);
        Note n = noteRepository.findByIdAndUserUid(id, uid)
                .orElseThrow(() -> new ResourceNotFoundException("Note", "id", id));
        if (req.getTitle()   != null) n.setTitle(req.getTitle());
        if (req.getContent() != null) n.setContent(req.getContent());
        if (req.getType()    != null) n.setType(req.getType());
        Note saved = noteRepository.save(n);
        String embedText = buildEmbedText(saved);
        ingestionPipeline.ingestChunked(uid, "note", saved.getId(), embedText);
        eventPublisher.publishEvent(new KnowledgeGraphTriggerEvent(
                uid, "note", saved.getId(), embedText));
        return toResponse(saved);
    }

    @Transactional
    public void delete(String uid, UUID id) {
        Note n = noteRepository.findByIdAndUserUid(id, uid)
                .orElseThrow(() -> new ResourceNotFoundException("Note", "id", id));
        log.info("Deleting note [{}] for user [{}]", id, uid);
        ingestionPipeline.delete(uid, "note", n.getId());
        eventPublisher.publishEvent(new KnowledgeGraphTriggerEvent(uid, "note", n.getId(), null));
        noteRepository.delete(n);
    }

    private String buildEmbedText(Note n) {
        String contentText = "";
        if (n.getContent() != null) {
            contentText = n.getContent().isTextual()
                    ? n.getContent().asText()
                    : n.getContent().toString();
        }
        return n.getTitle() + "\n" + contentText;
    }
}
