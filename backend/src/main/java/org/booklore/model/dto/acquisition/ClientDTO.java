package org.booklore.model.dto.acquisition;

import jakarta.validation.constraints.NotBlank;

public record ClientDTO(
        Long id,
        @NotBlank String name,
        @NotBlank String type,
        @NotBlank String url,
        String apiKey,
        String category,
        boolean enabled
) {
}
