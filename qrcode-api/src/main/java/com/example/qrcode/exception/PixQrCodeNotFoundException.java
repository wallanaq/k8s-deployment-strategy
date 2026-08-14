package com.example.qrcode.exception;

import java.util.UUID;

public class PixQrCodeNotFoundException extends RuntimeException {

    public PixQrCodeNotFoundException(UUID id) {
        super("QR code Pix não encontrado para o id: " + id);
    }
}
