-- =====================================================================
-- PsiGestor - esquema fisico (SQLite)
-- Modelo relacional derivado do MER em docs/MODELAGEM.md
-- Cada comando termina com ";" no fim da linha; triggers terminam em "END;"
-- (o carregador Database.criarEsquema() usa essas regras para dividir).
-- =====================================================================

CREATE TABLE IF NOT EXISTS usuario (
    id              INTEGER PRIMARY KEY AUTOINCREMENT,
    nome            TEXT    NOT NULL,
    login           TEXT    NOT NULL UNIQUE,
    senha_hash      TEXT    NOT NULL,         -- PBKDF2, nunca a senha pura
    senha_salt      TEXT    NOT NULL,
    chave_salt      TEXT    NOT NULL,         -- salt da chave de embrulho (KEK), derivada da senha
    papel           TEXT    NOT NULL CHECK (papel IN ('PSICOLOGO','SECRETARIA')),
    crp             TEXT,                     -- obrigatorio para PSICOLOGO (validado no service)
    percentual_repasse_clinica REAL NOT NULL DEFAULT 0 CHECK (percentual_repasse_clinica BETWEEN 0 AND 100),
    ativo           INTEGER NOT NULL DEFAULT 1,
    chave_cifrada   BLOB,                     -- chave do prontuario (DEK) cifrada pela KEK; so psicologo
    chave_iv        BLOB
);

CREATE TABLE IF NOT EXISTS convenio (
    id                    INTEGER PRIMARY KEY AUTOINCREMENT,
    nome                  TEXT NOT NULL UNIQUE,
    registro_ans          TEXT,
    valor_sessao          REAL NOT NULL DEFAULT 0 CHECK (valor_sessao >= 0),
    prazo_pagamento_dias  INTEGER NOT NULL DEFAULT 30 CHECK (prazo_pagamento_dias >= 0)
);

CREATE TABLE IF NOT EXISTS servico (
    id            INTEGER PRIMARY KEY AUTOINCREMENT,
    nome          TEXT    NOT NULL,
    codigo_tuss   TEXT,                       -- digitado pelo usuario (ver docs/PONTOS_DE_ATENCAO_LEGAIS.md)
    duracao_min   INTEGER NOT NULL DEFAULT 50 CHECK (duracao_min > 0),
    valor         REAL    NOT NULL DEFAULT 0 CHECK (valor >= 0)
);

CREATE TABLE IF NOT EXISTS paciente (
    id                  INTEGER PRIMARY KEY AUTOINCREMENT,
    nome                TEXT NOT NULL,
    cpf                 TEXT UNIQUE,
    data_nascimento     TEXT,                 -- ISO yyyy-MM-dd
    telefone            TEXT,
    email               TEXT,
    contato_emergencia  TEXT,
    responsavel_legal   TEXT,                 -- obrigatorio para menor de 18 (validado no service)
    convenio_id         INTEGER REFERENCES convenio(id) ON DELETE SET NULL,
    numero_carteirinha  TEXT,
    arquivado           INTEGER NOT NULL DEFAULT 0,   -- nunca apagamos: arquivamos
    criado_em           TEXT NOT NULL DEFAULT (datetime('now','localtime'))
);

CREATE TABLE IF NOT EXISTS consentimento (
    id           INTEGER PRIMARY KEY AUTOINCREMENT,
    paciente_id  INTEGER NOT NULL REFERENCES paciente(id) ON DELETE RESTRICT,
    tipo         TEXT    NOT NULL CHECK (tipo IN ('CONTRATO_TERAPEUTICO','CONTATO_WHATSAPP_EMAIL','ATENDIMENTO_ONLINE')),
    concedido    INTEGER NOT NULL,
    registrado_em TEXT   NOT NULL DEFAULT (datetime('now','localtime')),
    observacao   TEXT
);

CREATE TABLE IF NOT EXISTS atendimento (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    paciente_id      INTEGER NOT NULL REFERENCES paciente(id) ON DELETE RESTRICT,
    profissional_id  INTEGER NOT NULL REFERENCES usuario(id)  ON DELETE RESTRICT,
    servico_id       INTEGER NOT NULL REFERENCES servico(id)  ON DELETE RESTRICT,
    convenio_id      INTEGER REFERENCES convenio(id) ON DELETE SET NULL,
    inicio           TEXT NOT NULL,           -- ISO yyyy-MM-ddTHH:mm
    fim              TEXT NOT NULL,
    status           TEXT NOT NULL DEFAULT 'AGENDADO'
                     CHECK (status IN ('AGENDADO','CONFIRMADO','REALIZADO','FALTOU','CANCELADO')),
    modalidade       TEXT NOT NULL DEFAULT 'PRESENCIAL' CHECK (modalidade IN ('PRESENCIAL','ONLINE')),
    valor            REAL NOT NULL DEFAULT 0,
    pago             INTEGER NOT NULL DEFAULT 0,
    forma_pagamento  TEXT,
    recibo_emitido   INTEGER NOT NULL DEFAULT 0,  -- marcado apos emitir no Receita Saude
    CHECK (fim > inicio)
);

CREATE INDEX IF NOT EXISTS idx_atendimento_inicio ON atendimento(inicio);

-- Prontuario / registro documental: conteudo SEMPRE cifrado (AES-GCM)
CREATE TABLE IF NOT EXISTS evolucao (
    id               INTEGER PRIMARY KEY AUTOINCREMENT,
    paciente_id      INTEGER NOT NULL REFERENCES paciente(id) ON DELETE RESTRICT,
    atendimento_id   INTEGER REFERENCES atendimento(id) ON DELETE RESTRICT,
    profissional_id  INTEGER NOT NULL REFERENCES usuario(id) ON DELETE RESTRICT,
    registrado_em    TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    conteudo_cifrado BLOB NOT NULL,
    iv               BLOB NOT NULL,
    retifica_id      INTEGER REFERENCES evolucao(id)  -- retificacao aponta para o registro corrigido
);

-- Registro documental nao pode ser editado nem apagado: so retificado.
CREATE TRIGGER IF NOT EXISTS trg_evolucao_sem_update
BEFORE UPDATE ON evolucao
BEGIN
    SELECT RAISE(ABORT, 'Registro de prontuario nao pode ser alterado. Use retificacao.');
END;

CREATE TRIGGER IF NOT EXISTS trg_evolucao_sem_delete
BEFORE DELETE ON evolucao
BEGIN
    SELECT RAISE(ABORT, 'Registro de prontuario nao pode ser apagado (guarda minima de 5 anos).');
END;

-- Trilha de auditoria (quem acessou o que e quando) - somente insercao
CREATE TABLE IF NOT EXISTS log_acesso (
    id          INTEGER PRIMARY KEY AUTOINCREMENT,
    usuario_id  INTEGER REFERENCES usuario(id),
    acao        TEXT NOT NULL,
    entidade    TEXT,
    entidade_id INTEGER,
    data_hora   TEXT NOT NULL DEFAULT (datetime('now','localtime')),
    detalhe     TEXT                      -- ex.: login tentado em LOGIN_FALHOU
);

CREATE TRIGGER IF NOT EXISTS trg_log_sem_update
BEFORE UPDATE ON log_acesso
BEGIN
    SELECT RAISE(ABORT, 'Log de acesso e somente leitura.');
END;

CREATE TRIGGER IF NOT EXISTS trg_log_sem_delete
BEFORE DELETE ON log_acesso
BEGIN
    SELECT RAISE(ABORT, 'Log de acesso e somente leitura.');
END;
