package com.example.qrcode.dto;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

import java.math.BigDecimal;

public record PixQrCodeRequest(
        @NotBlank(message = "chavePix é obrigatório")
        String chavePix,

        @NotBlank(message = "nomeRecebedor é obrigatório")
        @Size(max = 25, message = "nomeRecebedor deve ter no máximo 25 caracteres")
        String nomeRecebedor,

        @NotBlank(message = "cidadeRecebedor é obrigatório")
        @Size(max = 15, message = "cidadeRecebedor deve ter no máximo 15 caracteres")
        String cidadeRecebedor,

        @DecimalMin(value = "0.0", inclusive = false, message = "valor deve ser numérico positivo")
        BigDecimal valor,

        String txid,

        String descricao
) {
}
