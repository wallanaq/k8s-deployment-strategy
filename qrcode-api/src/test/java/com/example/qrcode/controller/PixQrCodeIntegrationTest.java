package com.example.qrcode.controller;

import com.example.qrcode.dto.PixQrCodeRequest;
import com.example.qrcode.dto.PixQrCodeResponse;
import com.example.qrcode.repository.PixQrCodeRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PixQrCodeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private PixQrCodeRepository repository;

    @Test
    void createsPixQrCodeAndPersistsIt() {
        PixQrCodeRequest request = new PixQrCodeRequest(
                "exemplo@email.com",
                "Fulano de Tal",
                "Brasilia",
                new BigDecimal("10.00"),
                "PEDIDO123",
                "Pagamento de exemplo"
        );

        ResponseEntity<PixQrCodeResponse> response = restTemplate.postForEntity(
                "/api/pix/qrcodes", request, PixQrCodeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(response.getHeaders().getLocation()).isNotNull();

        PixQrCodeResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.id()).isNotNull();
        assertThat(body.payload()).contains("br.gov.bcb.pix").isNotBlank();
        assertThat(body.qrCodeBase64()).startsWith("data:image/png;base64,");
        assertThat(body.criadoEm()).isNotNull();
        assertThat(body.criadoEm().getNano() % 1000).isEqualTo(0);
        assertThat(body.cancelledAt()).isNull();

        byte[] png = Base64.getDecoder().decode(body.qrCodeBase64().substring("data:image/png;base64,".length()));
        assertThat(png.length).isGreaterThan(0);
        assertThat(png[0]).isEqualTo((byte) 0x89);

        Optional<com.example.qrcode.entity.PixQrCode> saved = repository.findById(body.id());
        assertThat(saved).isPresent();
        assertThat(saved.get().getChavePix()).isEqualTo("exemplo@email.com");
        assertThat(saved.get().getPayload()).isEqualTo(body.payload());
    }

    @Test
    void getByIdReturnsPreviouslyCreatedQrCode() {
        PixQrCodeRequest request = new PixQrCodeRequest(
                "chave@pix.com", "Nome Teste", "Cidade Teste", null, null, null);

        ResponseEntity<PixQrCodeResponse> created = restTemplate.postForEntity(
                "/api/pix/qrcodes", request, PixQrCodeResponse.class);
        assertThat(created.getBody()).isNotNull();

        ResponseEntity<PixQrCodeResponse> fetched = restTemplate.getForEntity(
                "/api/pix/qrcodes/" + created.getBody().id(), PixQrCodeResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(created.getBody().id());
        assertThat(fetched.getBody().payload()).contains("62070503***");
    }

    @Test
    void createWithoutChavePixReturnsBadRequest() {
        PixQrCodeRequest request = new PixQrCodeRequest(
                "", "Nome Teste", "Cidade Teste", null, null, null);

        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/pix/qrcodes", request, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void getByIdWithNonUuidPathVariableReturnsBadRequestWithClearMessage() {
        ResponseEntity<String> response = restTemplate.getForEntity(
                "/api/pix/qrcodes/nao-e-um-uuid", String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
        assertThat(response.getBody()).contains("id").contains("nao-e-um-uuid");
    }

    @Test
    void cancelSetsAndReturnsCancelledAt() {
        UUID id = createQrCode();

        ResponseEntity<PixQrCodeResponse> response = restTemplate.postForEntity(
                "/api/pix/qrcodes/" + id + "/cancel", null, PixQrCodeResponse.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().id()).isEqualTo(id);
        assertThat(response.getBody().cancelledAt()).isNotNull();
        // TIMESTAMPTZ only stores microsecond precision; asserting this
        // directly (rather than relying on a nanosecond-precision clock to
        // expose a mismatch) is what actually catches the bug where the
        // in-memory value returned right after the write doesn't match what
        // a later read of the same row returns -- see cancelIsIdempotent.
        assertThat(response.getBody().cancelledAt().getNano() % 1000).isEqualTo(0);

        Optional<com.example.qrcode.entity.PixQrCode> saved = repository.findById(id);
        assertThat(saved).isPresent();
        assertThat(saved.get().getCancelledAt()).isNotNull();
        assertThat(saved.get().isCancelled()).isTrue();
    }

    @Test
    void cancelIsIdempotent() {
        UUID id = createQrCode();

        ResponseEntity<PixQrCodeResponse> first = restTemplate.postForEntity(
                "/api/pix/qrcodes/" + id + "/cancel", null, PixQrCodeResponse.class);
        ResponseEntity<PixQrCodeResponse> second = restTemplate.postForEntity(
                "/api/pix/qrcodes/" + id + "/cancel", null, PixQrCodeResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(first.getBody()).isNotNull();
        assertThat(second.getBody()).isNotNull();
        assertThat(second.getBody().cancelledAt()).isEqualTo(first.getBody().cancelledAt());
    }

    @Test
    void cancelNonexistentIdReturnsNotFound() {
        ResponseEntity<String> response = restTemplate.postForEntity(
                "/api/pix/qrcodes/" + UUID.randomUUID() + "/cancel", null, String.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    void getByIdOnCancelledQrCodeStillReturnsItWithCancelledStatus() {
        UUID id = createQrCode();
        restTemplate.postForEntity("/api/pix/qrcodes/" + id + "/cancel", null, PixQrCodeResponse.class);

        ResponseEntity<PixQrCodeResponse> fetched = restTemplate.getForEntity(
                "/api/pix/qrcodes/" + id, PixQrCodeResponse.class);

        assertThat(fetched.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(fetched.getBody()).isNotNull();
        assertThat(fetched.getBody().id()).isEqualTo(id);
        assertThat(fetched.getBody().cancelledAt()).isNotNull();
    }

    private UUID createQrCode() {
        PixQrCodeRequest request = new PixQrCodeRequest(
                "chave@pix.com", "Nome Teste", "Cidade Teste", null, null, null);

        ResponseEntity<PixQrCodeResponse> created = restTemplate.postForEntity(
                "/api/pix/qrcodes", request, PixQrCodeResponse.class);
        assertThat(created.getBody()).isNotNull();

        return created.getBody().id();
    }
}
