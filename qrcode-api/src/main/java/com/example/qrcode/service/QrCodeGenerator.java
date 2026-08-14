package com.example.qrcode.service;

import com.example.qrcode.exception.QrCodeGenerationException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.WriterException;
import com.google.zxing.client.j2se.MatrixToImageWriter;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel;
import org.springframework.stereotype.Component;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Map;

@Component
public class QrCodeGenerator {

    private static final int DEFAULT_SIZE = 300;

    public byte[] generatePng(String content) {
        return generatePng(content, DEFAULT_SIZE);
    }

    public byte[] generatePng(String content, int size) {
        try {
            Map<EncodeHintType, Object> hints = Map.of(EncodeHintType.ERROR_CORRECTION, ErrorCorrectionLevel.M);

            BitMatrix matrix = new QRCodeWriter().encode(content, BarcodeFormat.QR_CODE, size, size, hints);

            try (ByteArrayOutputStream outputStream = new ByteArrayOutputStream()) {
                MatrixToImageWriter.writeToStream(matrix, "PNG", outputStream);
                return outputStream.toByteArray();
            }
        } catch (WriterException | IOException e) {
            throw new QrCodeGenerationException("Falha ao gerar imagem do QR code", e);
        }
    }
}
