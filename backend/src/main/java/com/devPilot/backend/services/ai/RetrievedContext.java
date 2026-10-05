package com.devPilot.backend.services.ai;

import java.util.List;

import com.devPilot.backend.dto.CitationDto;

public record RetrievedContext(
        List<CitationDto> citations,
        String contextText) {
}