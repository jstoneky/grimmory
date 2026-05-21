package org.booklore.model.dto.acquisition;

import jakarta.validation.constraints.NotBlank;

public record AddToWantedRequest(
        @NotBlank String title,
        String author,
        String isbn13,
        String isbn10,
        String provider,
        String providerBookId,
        String thumbnailUrl
) {
}
