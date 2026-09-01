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
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.Base64;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
class PixQrCodeIntegrationTest {

    @Container
    @ServiceConnection
    static PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:16");

    @Autowired
    private TestRestTemplate restTemplate;

    // Spy, not a plain @Autowired -- delegates to the real bean (so every
    // existing assertion against it keeps working unchanged) while also
    // letting the new cache tests verify how many times the repository
    // was actually invoked, which is the only way to prove caching (and
    // eviction) is really happening rather than just annotated.
    @MockitoSpyBean
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
        assertThat(body.canceladoEm()).isNull();

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
        assertThat(response.getBody().canceladoEm()).isNotNull();
        // TIMESTAMPTZ only stores microsecond precision; asserting this
        // directly (rather than relying on a nanosecond-precision clock to
        // expose a mismatch) is what actually catches the bug where the
        // in-memory value returned right after the write doesn't match what
        // a later read of the same row returns -- see cancelIsIdempotent.
        assertThat(response.getBody().canceladoEm().getNano() % 1000).isEqualTo(0);

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
        assertThat(second.getBody().canceladoEm()).isEqualTo(first.getBody().canceladoEm());
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
        assertThat(fetched.getBody().canceladoEm()).isNotNull();
    }

    @Test
    void repeatedGetByIdHitsRepositoryOnlyOnce() {
        UUID id = createQrCode();
        // The create() call above doesn't populate the "qrCodes" cache
        // (only findById() is @Cacheable), so both GETs below are exactly
        // what's under test.

        ResponseEntity<PixQrCodeResponse> first = restTemplate.getForEntity(
                "/api/pix/qrcodes/" + id, PixQrCodeResponse.class);
        ResponseEntity<PixQrCodeResponse> second = restTemplate.getForEntity(
                "/api/pix/qrcodes/" + id, PixQrCodeResponse.class);

        assertThat(first.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(second.getBody()).isEqualTo(first.getBody());
        verify(repository, times(1)).findById(id);
    }

    @Test
    void cancelEvictsTheCacheEntryForThatId() {
        UUID id = createQrCode();

        // Cache miss: populates the "qrCodes" entry for id (1st findById call).
        restTemplate.getForEntity("/api/pix/qrcodes/" + id, PixQrCodeResponse.class);

        // cancel() calls repository.findById() itself to load the entity to
        // cancel -- that's a second, always-present call independent of the
        // "qrCodes" cache (cancel() isn't @Cacheable). It's on top of, not
        // instead of, the eviction being tested below.
        restTemplate.postForEntity("/api/pix/qrcodes/" + id + "/cancel", null, PixQrCodeResponse.class);

        // If eviction worked, this is a fresh cache miss (3rd findById
        // call) rather than a stale cached "active" response.
        ResponseEntity<PixQrCodeResponse> afterCancel = restTemplate.getForEntity(
                "/api/pix/qrcodes/" + id, PixQrCodeResponse.class);

        assertThat(afterCancel.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(afterCancel.getBody()).isNotNull();
        assertThat(afterCancel.getBody().canceladoEm()).isNotNull();
        verify(repository, times(3)).findById(id);
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
