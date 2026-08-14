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
            criadoEm = Instant.now();
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
}
