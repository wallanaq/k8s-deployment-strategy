package com.example.qrcode.dto;

import java.time.Instant;
import java.util.UUID;

public record PixQrCodeResponse(
        UUID id,
        String payload,
        String qrCodeBase64,
        Instant criadoEm,
        Instant canceladoEm
) {
}
