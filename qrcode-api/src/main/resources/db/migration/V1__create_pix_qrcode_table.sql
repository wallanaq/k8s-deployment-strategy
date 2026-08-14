CREATE TABLE pix_qrcode (
    id             UUID PRIMARY KEY,
    chave_pix      VARCHAR(255)   NOT NULL,
    nome_recebedor VARCHAR(25)    NOT NULL,
    cidade_recebedor VARCHAR(15)  NOT NULL,
    valor          NUMERIC(12, 2),
    txid           VARCHAR(255)   NOT NULL,
    descricao      TEXT,
    payload        TEXT           NOT NULL,
    criado_em      TIMESTAMPTZ    NOT NULL
);
