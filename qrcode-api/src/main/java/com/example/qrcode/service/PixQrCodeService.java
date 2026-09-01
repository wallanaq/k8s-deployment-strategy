package com.example.qrcode.service;

import com.example.qrcode.dto.PixQrCodeRequest;
import com.example.qrcode.dto.PixQrCodeResponse;
import com.example.qrcode.entity.PixQrCode;
import com.example.qrcode.exception.PixQrCodeNotFoundException;
import com.example.qrcode.repository.PixQrCodeRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Base64;
import java.util.UUID;

@Service
public class PixQrCodeService {

    private static final String DEFAULT_TXID = "***";

    private final PixQrCodeRepository repository;
    private final PixPayloadBuilder payloadBuilder;
    private final QrCodeGenerator qrCodeGenerator;

    public PixQrCodeService(PixQrCodeRepository repository, PixPayloadBuilder payloadBuilder, QrCodeGenerator qrCodeGenerator) {
        this.repository = repository;
        this.payloadBuilder = payloadBuilder;
        this.qrCodeGenerator = qrCodeGenerator;
    }

    @Transactional
    public PixQrCodeResponse create(PixQrCodeRequest request) {
        String txid = (request.txid() == null || request.txid().isBlank()) ? DEFAULT_TXID : request.txid();

        String payload = payloadBuilder.build(request.chavePix(),
                request.nomeRecebedor(),
                request.cidadeRecebedor(),
                request.valor(),
                txid,
                request.descricao());

        PixQrCode entity = new PixQrCode(request.chavePix(),
                request.nomeRecebedor(),
                request.cidadeRecebedor(),
                request.valor(),
                txid,
                request.descricao(),
                payload);

        PixQrCode saved = repository.save(entity);

        return toResponse(saved);
    }

    @Transactional(readOnly = true)
    public PixQrCodeResponse findById(UUID id) {
        PixQrCode entity = repository.findById(id).orElseThrow(() -> new PixQrCodeNotFoundException(id));
        return toResponse(entity);
    }

    /**
     * Logical deletion: sets cancelledAt instead of removing the row, so the
     * record survives for audit/reconciliation. Idempotent -- cancelling an
     * already-cancelled QR code is not an error; it just returns the
     * existing cancelled state (entity.cancel() is itself a no-op once
     * cancelledAt is set), so a retried request never fails.
     */
    @Transactional
    public PixQrCodeResponse cancel(UUID id) {
        PixQrCode entity = repository.findById(id).orElseThrow(() -> new PixQrCodeNotFoundException(id));
        entity.cancel();
        PixQrCode saved = repository.save(entity);
        return toResponse(saved);
    }

    private PixQrCodeResponse toResponse(PixQrCode entity) {
        byte[] png = qrCodeGenerator.generatePng(entity.getPayload());
        String qrCodeBase64 = "data:image/png;base64," + Base64.getEncoder().encodeToString(png);
        return new PixQrCodeResponse(entity.getId(), entity.getPayload(), qrCodeBase64, entity.getCriadoEm(), entity.getCancelledAt());
    }
}
