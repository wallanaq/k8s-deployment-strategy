package com.example.qrcode.service;

import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

@Component
public class PixPayloadBuilder {

    private static final String ID_PAYLOAD_FORMAT_INDICATOR = "00";
    private static final String ID_POINT_OF_INITIATION_METHOD = "01";
    private static final String ID_MERCHANT_ACCOUNT_INFORMATION = "26";
    private static final String ID_MERCHANT_ACCOUNT_INFORMATION_GUI = "00";
    private static final String ID_MERCHANT_ACCOUNT_INFORMATION_KEY = "01";
    private static final String ID_MERCHANT_ACCOUNT_INFORMATION_DESCRIPTION = "02";
    private static final String ID_MERCHANT_CATEGORY_CODE = "52";
    private static final String ID_TRANSACTION_CURRENCY = "53";
    private static final String ID_TRANSACTION_AMOUNT = "54";
    private static final String ID_COUNTRY_CODE = "58";
    private static final String ID_MERCHANT_NAME = "59";
    private static final String ID_MERCHANT_CITY = "60";
    private static final String ID_ADDITIONAL_DATA_FIELD_TEMPLATE = "62";
    private static final String ID_ADDITIONAL_DATA_FIELD_TXID = "05";
    private static final String ID_CRC16 = "63";

    private static final String GUI_PIX = "br.gov.bcb.pix";
    private static final String MERCHANT_CATEGORY_CODE = "0000";
    private static final String TRANSACTION_CURRENCY_BRL = "986";
    private static final String COUNTRY_CODE_BR = "BR";

    private static final int MAX_NOME_RECEBEDOR_LENGTH = 25;
    private static final int MAX_CIDADE_RECEBEDOR_LENGTH = 15;

    public String build(String chavePix, String nomeRecebedor, String cidadeRecebedor, BigDecimal valor, String txid, String descricao) {
        StringBuilder payload = new StringBuilder();

        payload.append(tlv(ID_PAYLOAD_FORMAT_INDICATOR, "01"));
        payload.append(tlv(ID_POINT_OF_INITIATION_METHOD, valor != null ? "12" : "11"));
        payload.append(tlv(ID_MERCHANT_ACCOUNT_INFORMATION, merchantAccountInformation(chavePix, descricao)));
        payload.append(tlv(ID_MERCHANT_CATEGORY_CODE, MERCHANT_CATEGORY_CODE));
        payload.append(tlv(ID_TRANSACTION_CURRENCY, TRANSACTION_CURRENCY_BRL));

        if (valor != null) {
            payload.append(tlv(ID_TRANSACTION_AMOUNT, valor.setScale(2, java.math.RoundingMode.UNNECESSARY).toPlainString()));
        }

        payload.append(tlv(ID_COUNTRY_CODE, COUNTRY_CODE_BR));
        payload.append(tlv(ID_MERCHANT_NAME, truncateUpper(nomeRecebedor, MAX_NOME_RECEBEDOR_LENGTH)));
        payload.append(tlv(ID_MERCHANT_CITY, truncateUpper(cidadeRecebedor, MAX_CIDADE_RECEBEDOR_LENGTH)));
        payload.append(tlv(ID_ADDITIONAL_DATA_FIELD_TEMPLATE, tlv(ID_ADDITIONAL_DATA_FIELD_TXID, txid)));

        payload.append(ID_CRC16).append("04");
        String crc = String.format("%04X", crc16Ccitt(payload.toString()));
        payload.append(crc);

        return payload.toString();
    }

    private String merchantAccountInformation(String chavePix, String descricao) {
        StringBuilder sub = new StringBuilder();
        sub.append(tlv(ID_MERCHANT_ACCOUNT_INFORMATION_GUI, GUI_PIX));
        sub.append(tlv(ID_MERCHANT_ACCOUNT_INFORMATION_KEY, chavePix));

        if (descricao != null && !descricao.isBlank()) {
            sub.append(tlv(ID_MERCHANT_ACCOUNT_INFORMATION_DESCRIPTION, descricao));
        }

        return sub.toString();
    }

    private String truncateUpper(String value, int maxLength) {
        String upper = value.toUpperCase(Locale.ROOT);
        return upper.length() > maxLength ? upper.substring(0, maxLength) : upper;
    }

    private String tlv(String id, String value) {
        return id + String.format("%02d", value.length()) + value;
    }

    int crc16Ccitt(String data) {
        int crc = 0xFFFF;
        byte[] bytes = data.getBytes(StandardCharsets.UTF_8);
        for (byte b : bytes) {
            crc ^= (b & 0xFF) << 8;
            for (int i = 0; i < 8; i++) {
                if ((crc & 0x8000) != 0) {
                    crc = (crc << 1) ^ 0x1021;
                } else {
                    crc <<= 1;
                }
                crc &= 0xFFFF;
            }
        }
        return crc & 0xFFFF;
    }
}
