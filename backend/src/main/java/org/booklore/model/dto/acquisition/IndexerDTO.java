package org.booklore.model.dto.acquisition;

import jakarta.validation.constraints.NotBlank;

public record IndexerDTO(
        Long id,
        @NotBlank String name,
        @NotBlank String url,
        String apiKey,
        boolean enabled,
        int priority
) {
}
