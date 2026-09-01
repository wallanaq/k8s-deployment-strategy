package com.example.qrcode.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

@Entity
@Table(name = "pix_qrcode")
public class PixQrCode {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "chave_pix", nullable = false)
    private String chavePix;

    @Column(name = "nome_recebedor", nullable = false, length = 25)
    private String nomeRecebedor;

    @Column(name = "cidade_recebedor", nullable = false, length = 15)
    private String cidadeRecebedor;

    @Column(name = "valor", precision = 12, scale = 2)
    private BigDecimal valor;

    @Column(name = "txid", nullable = false)
    private String txid;

    @Column(name = "descricao")
    private String descricao;

    @Column(name = "payload", nullable = false, columnDefinition = "text")
    private String payload;

    @Column(name = "criado_em", nullable = false, updatable = false)
    private Instant criadoEm;

    // Logical deletion, not a physical DELETE FROM -- this is a payment
    // system and the historical record needs to survive cancellation for
    // audit/reconciliation. NULL means active; a non-null value means
    // cancelled and records when.
    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    protected PixQrCode() {
    }

    public PixQrCode(String chavePix, String nomeRecebedor, String cidadeRecebedor, BigDecimal valor, String txid, String descricao, String payload) {
        this.chavePix = chavePix;
        this.nomeRecebedor = nomeRecebedor;
        this.cidadeRecebedor = cidadeRecebedor;
        this.valor = valor;
        this.txid = txid;
        this.descricao = descricao;
        this.payload = payload;
    }

    @PrePersist
    void prePersist() {
        if (criadoEm == null) {
            // Truncated to microseconds: TIMESTAMPTZ only stores microsecond
            // precision, so an untruncated Instant.now() (nanosecond
            // resolution on some JVMs/OSes, e.g. Linux) would silently lose
            // precision on the round trip through Postgres. Truncating here
            // means the in-memory value already matches what gets persisted,
            // so a response returned right after a write is never out of
            // sync with what a later read of the same row returns.
            criadoEm = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }

    public UUID getId() {
        return id;
    }

    public void setId(UUID id) {
        this.id = id;
    }

    public String getChavePix() {
        return chavePix;
    }

    public void setChavePix(String chavePix) {
        this.chavePix = chavePix;
    }

    public String getNomeRecebedor() {
        return nomeRecebedor;
    }

    public void setNomeRecebedor(String nomeRecebedor) {
        this.nomeRecebedor = nomeRecebedor;
    }

    public String getCidadeRecebedor() {
        return cidadeRecebedor;
    }

    public void setCidadeRecebedor(String cidadeRecebedor) {
        this.cidadeRecebedor = cidadeRecebedor;
    }

    public BigDecimal getValor() {
        return valor;
    }

    public void setValor(BigDecimal valor) {
        this.valor = valor;
    }

    public String getTxid() {
        return txid;
    }

    public void setTxid(String txid) {
        this.txid = txid;
    }

    public String getDescricao() {
        return descricao;
    }

    public void setDescricao(String descricao) {
        this.descricao = descricao;
    }

    public String getPayload() {
        return payload;
    }

    public void setPayload(String payload) {
        this.payload = payload;
    }

    public Instant getCriadoEm() {
        return criadoEm;
    }

    public void setCriadoEm(Instant criadoEm) {
        this.criadoEm = criadoEm;
    }

    public Instant getCancelledAt() {
        return cancelledAt;
    }

    public void setCancelledAt(Instant cancelledAt) {
        this.cancelledAt = cancelledAt;
    }

    public boolean isCancelled() {
        return cancelledAt != null;
    }

    /**
     * Marks this QR code as cancelled at the current instant. Idempotent:
     * calling this on an already-cancelled QR code is a no-op, so a retried
     * cancel request never overwrites the original cancellation timestamp.
     * Truncated to microseconds for the same reason as prePersist() above --
     * matches TIMESTAMPTZ's actual storage precision, so the value returned
     * in the response right after cancelling is never out of sync with what
     * a later read of the same row returns.
     */
    public void cancel() {
        if (cancelledAt == null) {
            cancelledAt = Instant.now().truncatedTo(ChronoUnit.MICROS);
        }
    }
}
