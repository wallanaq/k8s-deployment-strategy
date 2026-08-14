package com.example.qrcode.service;

import org.junit.jupiter.api.Test;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;

class PixPayloadBuilderTest {

    private final PixPayloadBuilder builder = new PixPayloadBuilder();

    @Test
    void buildsKnownPayloadWithCorrectCrc16() {
        String payload = builder.build(
                "email@dominio.com",
                "Fulano de Tal",
                "Brasilia",
                new BigDecimal("10.00"),
                "PEDIDO123",
                null
        );

        String expected = "00020101021226390014br.gov.bcb.pix0117email@dominio.com"
                + "520400005303986540510.005802BR5913FULANO DE TAL6008BRASILIA"
                + "62130509PEDIDO12363042DF7";

        assertThat(payload).isEqualTo(expected);
        assertThat(payload).endsWith("63042DF7");
    }

    @Test
    void usesPointOfInitiationMethod11WhenValorIsAbsent() {
        String payload = builder.build("chave", "Nome", "Cidade", null, "PEDIDO", null);

        assertThat(payload).contains("010211");
        assertThat(payload).doesNotContain("5405");
    }

    @Test
    void truncatesNomeAndCidadeAndUppercasesThem() {
        String payload = builder.build(
                "chave",
                "um nome com mais de vinte e cinco caracteres",
                "uma cidade com mais de quinze",
                null,
                "PEDIDO",
                null
        );

        assertThat(payload).contains("5925UM NOME COM MAIS DE VINTE");
        assertThat(payload).contains("6015UMA CIDADE COM ");
    }

    @Test
    void crc16MatchesIndependentImplementation() {
        int crc = builder.crc16Ccitt("123456789");
        assertThat(String.format("%04X", crc)).isEqualTo("29B1");
    }
}
