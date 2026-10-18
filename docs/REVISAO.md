# Revisao de codigo - PsiGestor (src/main/java)

Escopo: todos os arquivos de `src/main/java` e `src/main/resources/db/schema.sql`, lidos integralmente.
Caminhos abaixo relativos a `src/main/java/br/com/kleber/psigestor/` (exceto o schema).
Nada foi executado; tudo o que esta listado vem de leitura do codigo. Itens marcados **(suspeita)** nao foram confirmados.

## 1. Resumo executivo

O projeto tem uma base boa de privacidade: AES-256-GCM com IV aleatorio por registro, chave de dados (DEK) por psicologo embrulhada por uma chave derivada da senha (PBKDF2), prontuario append-only com triggers, trilha de auditoria e nenhuma chave hardcoded. Nao ha SQL injection (tudo usa `PreparedStatement`; a unica concatenacao e o `VACUUM INTO`, com escape de aspas).

Os problemas mais relevantes estao fora da criptografia em si:

1. **Conversao de numeros quebrada** (`CadastrosView.numero`): `"150.00"` vira `15000`, `"7.5"` (percentual de repasse) vira `75`. Afeta precos de servico, valor de convenio e % de repasse, com impacto financeiro silencioso.
2. **Regras de autorizacao so na interface**: cadastro de servicos/convenios e alteracao de % de repasse chamam o DAO direto, sem `exigirSessao`, sem checar papel e sem auditoria. Nao existe como desativar um usuario (a coluna `ativo` nunca e escrita).
3. **LGPD**: dados cadastrais (CPF, telefone, e-mail, nascimento, contato de emergencia) e a agenda ficam em texto puro no banco, no backup e no relatorio TXT exportado; nao ha anonimizacao/eliminacao apos o prazo de guarda; nao ha bloqueio por inatividade nem limite de tentativas de login; a senha minima de 8 caracteres e a unica protecao do prontuario contra ataque offline ao arquivo `.db`.

Contagem: 1 Critica, 5 Altas, 9 Medias, 9 Baixas (total 24).

## 2. Achados por severidade

### CRITICA

[x] **C1. Parser de numero destroi valores com ponto decimal**
- Corrigido em: `util/Validadores.java` (`numeroBR`), `ui/CadastrosView.java`, `ui/EquipeView.java`
- `ui/CadastrosView.java:86-92` (`numero`), usado em `CadastrosView.java:40,65` e `ui/EquipeView.java:37,74`.
- Descricao: `s.replace(".", "").replace(",", ".")` trata todo ponto como separador de milhar. Entrada no formato "americano" ou colada de planilha e multiplicada por 10^casas.
- Cenario: cadastrar servico com valor `150.00` grava R$ 15.000,00; convenio `80.50` grava R$ 8.050,00; repasse `7.5` passa na validacao `0..100` do `EquipeView` e grava 75 %. O valor e copiado para cada atendimento (`service/AgendaService.java:57,59`), entao o erro contamina faturado, recebido e repasse sem aviso. Tambem aceita `NaN`/`Infinity`/`1e9` (`Double.parseDouble`), e `NaN` passa em `p < 0 || p > 100` (`EquipeView.java:38`).
- Correcao: usar `NumberFormat.getInstance(Locale.of("pt","BR"))` com `ParsePosition` (rejeitando texto sobrando), ou `BigDecimal` apos normalizar apenas a virgula; rejeitar NaN/Infinity/negativos; limitar maximo razoavel. Idealmente guardar dinheiro em centavos (ver M7).

### ALTA

**A1. Servicos, convenios e repasse sem checagem de sessao/papel nem auditoria**
- `ui/CadastrosView.java:39,64` (`ctx.servicos.salvar`, `ctx.convenios.salvar`), `ui/EquipeView.java:39` (`ctx.usuarios.atualizarRepasse`).
- Descricao: nao existe service para essas operacoes; a UI chama o DAO. `EquipeView` desabilita o botao para secretaria (linha 32), mas `CadastrosView` nao restringe nada, e servico/convenio definem o valor cobrado.
- Cenario: a secretaria altera valor/convenio e nada fica na trilha de auditoria; qualquer outra tela futura (ou codigo) que chame o DAO contorna a regra. Alterar % de repasse (dinheiro entre profissionais) nao e auditado.
- Correcao: criar service com `exigirSessao()`, regra de papel e `auditar(...)`; DAOs nao devem ser acessados pela UI.

**A2. Impossivel desativar/revogar usuario**
- `dao/UsuarioDAO.java` (nenhum `UPDATE ... ativo`), `service/AutenticacaoService.java` (sem metodo), `ui/EquipeView.java` (sem botao). Busca por `setAtivo` so acha o mapeador do DAO.
- Descricao: `entrar` filtra por `isAtivo` (`AutenticacaoService.java:57`), mas nada grava `ativo = 0`.
- Cenario: secretaria ou psicologo que saiu da clinica mantem acesso a agenda, dados cadastrais e exportacao de dados de todos os pacientes para sempre (LGPD art. 46: controle de acesso).
- Correcao: `desativar(usuarioId)` restrito a psicologo, com auditoria; impedir desativar o ultimo psicologo; nao apagar (FK RESTRICT).

[x] **A3. Reativar atendimento cancelado burla a verificacao de conflito**
- Corrigido em: `model/StatusAtendimento.java` (`podeIrPara`), `service/AgendaService.java` (`alterarStatus`), `dao/AtendimentoDAO.java` (`buscar`)
- `service/AgendaService.java:66-70` e `dao/AtendimentoDAO.java:53-55`.
- Descricao: `alterarStatus` aceita qualquer transicao e nao revalida conflito. `contarConflitos` ignora CANCELADO (`AtendimentoDAO.java:68`).
- Cenario: 14h cancelado; outro paciente e agendado as 14h; o usuario clica "Confirmar" no cancelado: dois atendimentos ativos no mesmo horario. Tambem permite REALIZADO -> AGENDADO, FALTOU -> REALIZADO etc., alterando faturamento retroativamente sem registrar o status anterior. `status` null causaria NPE (`status.name()`).
- Correcao: maquina de estados explicita; ao sair de CANCELADO chamar `contarConflitos(..., id)`; registrar status anterior na auditoria.

[x] **A4. Updates "silenciosos" em id inexistente**
- Corrigido em: `dao/AtendimentoDAO.java` (`executar`), `dao/PacienteDAO.java`, `dao/ConvenioDAO.java`, `dao/ServicoDAO.java`
- `dao/AtendimentoDAO.java:126-131` (`executar` ignora o retorno de `executeUpdate`), `dao/PacienteDAO.java:39`, `dao/ConvenioDAO.java:25`, `dao/ServicoDAO.java:26`.
- Cenario: `registrarPagamento(999, "PIX")` retorna sucesso e grava no log `PAGAMENTO atendimento 999` sem nada ter mudado. A auditoria passa a conter eventos que nao ocorreram.
- Correcao: verificar `executeUpdate() == 1` e lancar `RegraNegocioException("Atendimento nao encontrado.")`.

**A5. Dados cadastrais em texto puro: banco, backup e exportacao**
- `service/BackupService.java:35` (`VACUUM INTO`, copia integral nao cifrada), `service/PacienteService.java:98-130` (TXT puro, nome previsivel `dados_paciente_<id>.txt`, sobrescreve silenciosamente), `resources/db/schema.sql:39-52`.
- Descricao: so o conteudo de `evolucao` e cifrado. CPF, nascimento, telefone, e-mail, contato de emergencia, convenio/carteirinha e a agenda (quem consulta com quem e quando, dado de saude por inferencia) ficam legiveis a quem copiar `~/PsiGestor/psigestor.db`. O comentario do `BackupService` (linhas 14-16) reconhece isso; a unica mitigacao e um aviso na tela.
- Cenario: notebook roubado sem BitLocker, pendrive de backup perdido ou relatorio LGPD esquecido em Downloads: vazamento de dados pessoais de pacientes de psicologia.
- Correcao: cifrar backup e exportacao (AES-GCM com senha do usuario) ou usar SQLite cifrado (SQLCipher); restringir permissoes dos arquivos (B3); considerar cifrar colunas sensiveis de `paciente`.

### MEDIA

**M1. Sem limite de tentativas, sem bloqueio por inatividade; sessao nunca expira**
- `service/AutenticacaoService.java:54-71`, `service/Contexto.java:53-69`, `util/Sessao.java`.
- Descricao: o login pode ser tentado indefinidamente; a falha e auditada com `usuario_id = null` e sem o login tentado (linha 60), entao a trilha nao permite identificar a conta atacada. A sessao (com a DEK em memoria) dura ate "Sair".
- Cenario: computador deixado logado em consultorio compartilhado exibe o prontuario a qualquer pessoa (sigilo, Codigo de Etica art. 9).
- Correcao: atraso exponencial/bloqueio temporario por login; temporizador de inatividade que chama `ctx.sair()`; registrar o login tentado no log.

**M2. Auditoria nao e atomica com a operacao**
- `service/ProntuarioService.java:41-43`, `service/PacienteService.java:48-49`, `service/AgendaService.java:61-62` e demais (nenhuma usa transacao; `Database` mantem `autoCommit=true`).
- Cenario: o registro de prontuario e inserido, depois `auditar` falha (disco cheio/lock): o usuario ve erro, repete e cria registro duplicado que nunca pode ser apagado (triggers); ou o registro existe sem entrada de log. Na leitura, `listar` audita antes de decifrar (`ProntuarioService.java:50`), registrando leitura mesmo que ela falhe.
- Correcao: utilitario `transacao(Runnable)` em `Contexto` (setAutoCommit(false)/commit/rollback) englobando operacao + log.

**M3. Um registro corrompido impede a leitura de todo o prontuario do paciente**
- `service/ProntuarioService.java:52-56` + `util/Criptografia.java:101-103`.
- Descricao: `decifrar` lanca `RegraNegocioException`; o `stream().map` nao trata, entao um unico registro com tag invalida derruba a lista inteira.
- Correcao: capturar por registro, mostrar "[registro ilegivel #id]" e auditar o incidente.

**M4. Ciphertext sem dados autenticados associados (AAD)**
- `util/Criptografia.java:84-94`, `service/ProntuarioService.java:40-42`.
- Descricao: GCM autentica o conteudo, mas nao o vinculo com `paciente_id`, `profissional_id`, `registrado_em`, `retifica_id` (colunas em claro). Quem puder escrever no `.db` (removendo antes a trigger, o que o arquivo permite) pode mover um registro cifrado de um paciente para outro ou alterar metadados; a decifragem continua valida. Impacto real depende do modelo de ameaca; trate como **(suspeita)** de risco de integridade.
- Correcao: passar `paciente_id|profissional_id|iv` como AAD (`Cipher.updateAAD`).

**M5. PBKDF2-HMAC-SHA256 com 210.000 iteracoes abaixo da recomendacao atual; senha minima fraca**
- `util/Criptografia.java:29` (o comentario atribui 210.000 ao SHA-256), `service/AutenticacaoService.java:14`.
- Descricao: pelo que conheco do OWASP Password Storage Cheat Sheet, 210.000 e o valor para PBKDF2-SHA512 e ~600.000 para SHA-256 (confirme na versao vigente). Como a KEK que protege todo o prontuario deriva so da senha, e o arquivo `.db`/backup permite ataque offline ilimitado, a robustez da senha e a unica barreira.
- Correcao: aumentar iteracoes (gravando a contagem por usuario para permitir migracao futura), senha minima 12 ou passphrase, checagem contra senhas comuns; considerar Argon2id se aceitarem dependencia.

**M6. Troca de senha nao rotaciona a DEK**
- `service/AutenticacaoService.java:77-102,110-120`.
- Descricao: so o embrulho muda. Um backup ou copia anterior do `.db` continua desembrulhavel com a senha antiga e entrega a mesma DEK que protege tambem os registros novos. Para usuarios migrados, a DEK e `PBKDF2(senha_original, salt_antigo)` (linha 115), derivavel da senha original para sempre se o salt antigo estiver em algum backup.
- Cenario: senha vazada -> troca de senha -> o vazamento nao e remediado para dados ja copiados.
- Correcao: documentar a limitacao; oferecer "rotacionar chave" (DEK nova para registros novos, DEKs antigas mantidas embrulhadas, versao da chave em cada linha de `evolucao`).

**M7. Dinheiro em `double`**
- `model/Atendimento.java:15`, `service/FinanceiroService.java:30-37,64`, `schema.sql` (`REAL`).
- Descricao: somas acumuladas sem arredondar em `resumo` (so `repasse` usa `arred`), com risco de divergencia de centavos entre cards e tabela de repasse.
- Correcao: `BigDecimal` ou inteiro em centavos, com `RoundingMode` definido.

**M8. Direito de eliminacao/anonimizacao nao implementado**
- `service/PacienteService.java:53-63` (so arquivar), `schema.sql` (FK `ON DELETE RESTRICT`, triggers de delete). Detalhes na secao 3.5.
- Correcao: `anonimizarPaciente(id)` (substitui nome/CPF/contatos por valores neutros mantendo id e vinculos financeiros), restrito a psicologo, apos `criado_em + 5 anos`, com auditoria; politica documentada.

[x] **M9. Datas e validacoes de agenda fracas**
- Corrigido em: `service/AgendaService.java` (janela de datas, `verificarConflitos`), `dao/AtendimentoDAO.java` (`contarConflitosPaciente`), `ui/AgendaView.java` (`montarInicio`, `diaSelecionado`), `service/Contexto.java` (`hoje`)
- `service/AgendaService.java:25,35-37`, `ui/AgendaView.java:33-35,69,129-135`.
- Descricao: aceita datas no passado e em anos absurdos; conflito checado so por profissional, nao por paciente (o mesmo paciente pode ser marcado em horarios sobrepostos); `data.getValue()` null (DatePicker limpo) causa NPE dentro de `executar`, exibido como "Erro inesperado: null"; `dia.getValue()` null em `AgendaView.carregar` (e nos botoes `<`/`>`) lanca NPE fora de `Ui.executar`, na thread JavaFX.
- Correcao: validar `inicio` (janela razoavel; passado so com confirmacao), conflito por paciente, tratar DatePicker nulo.

### BAIXA

**B1. Excecoes engolidas sem log; mensagens internas ao usuario**
- `ui/Ui.java:103-113`, `dao/DaoException.java:5`, `service/BackupService.java:40`.
- Nao ha logger nem `printStackTrace` no projeto. `Ui.executar` mostra `"Erro inesperado: " + e.getMessage()` e descarta o stack trace; `DaoException` expoe mensagens do SQLite (ex.: "FOREIGN KEY constraint failed", caminhos). Nao ha `Thread.setDefaultUncaughtExceptionHandler`. Sugestao: `java.util.logging` em arquivo local sem dados de paciente e mensagem generica ao usuario.

**B2. Telas chamam servicos fora de `Ui.executar`**
- `ui/PainelView.java:24-57`, `ui/EquipeView.java:50`, `ui/PrivacidadeView.java:25`, `ui/TelaPrincipal.java:61` (`botoes.get(0).fire()` abre o Painel na criacao da tela principal). Uma `DaoException` ali sobe sem tratamento e a janela pode ficar em branco.

**B3. Arquivos criados com permissoes padrao**
- `ui/PsiGestorApp.java:21-23`, `service/PacienteService.java:123-125`, `service/BackupService.java:31-35`. No Windows herdam a ACL da pasta de destino; considerar restringir a ACL do diretorio `PsiGestor` e dos exports.

**B4. Backup**: nome com precisao de segundos; se o arquivo existir, `VACUUM INTO` falha com mensagem tecnica (`BackupService.java:32,40`). Nao ha verificacao de integridade do arquivo gerado nem rotina de restauracao.

**B5. Enumeracao de usuarios**
- `service/AutenticacaoService.java:59`: usuario inexistente/inativo nao calcula PBKDF2, resposta muito mais rapida que senha errada (enumeracao por tempo). `cadastrar` verifica login duplicado (linha 29) antes de checar a sessao (linha 32). Impacto baixo em app desktop.

**B6. Senha em memoria**
- [x] `PBEKeySpec` nunca chama `clearPassword()` (`util/Criptografia.java:108`), entao a copia interna do `char[]` permanece.
  - Corrigido em: `util/Criptografia.java` (`pbkdf2`)
- A UI converte `String` -> `char[]` (`ui/TelaLogin.java:41`, `TrocaSenhaDialog.java:47`) e a `String` imutavel nao e zerada. `AutenticacaoService.cadastrar` nao zera a senha (so `entrar` e `trocarSenha` zeram). A `SecretKeySpec` da DEK permanece na `Sessao` apos `sair()` ate o GC. Mitigacao parcial; documentar.

**B7. Validacoes de paciente incompletas**
- `service/PacienteService.java:25-51`: sem limite de tamanho de campos, telefone nao validado, `p == null` gera NPE, data de nascimento muito antiga aceita, `convenioId` inexistente cai em `DaoException` com mensagem de FK. A UI muta o objeto `Paciente` da tabela antes de validar (`ui/PacientesView.java:111-119`); se a validacao falha, a linha exibe dados nao salvos ate recarregar.

**B8. Entradas sem validacao em consentimento/pagamento; leituras sem sessao**
- `service/PacienteService.java:78-82` (`tipo == null` -> NPE; paciente inexistente -> `DaoException` de FK), `service/AgendaService.java:72-76` (forma de pagamento livre/nula; pagar atendimento cancelado ou ja pago sobrescreve a forma; pagamento so e contabilizado para REALIZADO, `FinanceiroService.java:34-37`). `PacienteService.aniversariantes` (linha 74) e `AgendaService.doDia/periodo` (85-91) nao chamam `exigirSessao()`.

**B9. Detalhes menores**
- `dao/PacienteDAO.java:65-69`: `LIKE` sem escapar `%`/`_` digitados.
- `dao/AtendimentoDAO.java:76`, `dao/PacienteDAO.java:59`, `dao/UsuarioDAO.java:89`, `util/Database.java:59`: `rs.getInt(1)` sem `rs.next()` so funciona porque o sqlite-jdbc pre-posiciona o cursor; nao e portavel.
- Conexao JDBC unica e nao sincronizada (`util/Database.java:30`): segura enquanto tudo roda na thread JavaFX; qualquer uso futuro de `Task`/thread de fundo (ex.: backup, PBKDF2 sem congelar a UI) causara corrida.
- `dao/ConsentimentoDAO.java:47-53`: `vigente` carrega todo o historico a cada checagem; `ServicoDAO.buscar` lista tudo e filtra em memoria (irrelevante nesta escala).
- [x] `convenio.prazo_pagamento_dias` sem `CHECK` (aceita negativo).
  - Corrigido em: `resources/db/schema.sql` (CHECK), `util/Database.java` (migracao v2 com triggers para bancos existentes)

## 3. LGPD e prontuario (secao especifica)

### 3.1 Criptografia (verificado)
| Item | Situacao |
|---|---|
| Algoritmo/modo | AES-256-GCM (`AES/GCM/NoPadding`), tag de 128 bits - adequado (`util/Criptografia.java:88`). |
| IV | 12 bytes de `SecureRandom` novo a cada cifragem, gravado junto do texto (`:86-87`). Aceitavel. |
| Chave | DEK aleatoria de 256 bits por psicologo (`:59-63`), embrulhada por KEK = PBKDF2(senha, salt proprio) (`AutenticacaoService.java:123-129`). **Nenhuma chave hardcoded** (nao ha constantes de chave/segredo no codigo). |
| Senha | PBKDF2-HMAC-SHA256, salt de 16 bytes, comparacao em tempo constante (`:48-52`). Iteracoes abaixo do atual (M5). |
| AAD | Nao usado (M4). |
| Recuperacao | Inexistente: senha perdida = prontuario irrecuperavel. E comunicado ao usuario (`TelaLogin.java:59-60`, `TrocaSenhaDialog.java:26`), mas e risco de disponibilidade; considerar chave de recuperacao guardada offline. |
| Metadados | `paciente_id`, `profissional_id`, `atendimento_id`, `registrado_em`, `retifica_id` ficam em claro (revelam frequencia e cronologia). |

### 3.2 Vazamento em logs, toString e mensagens
- [x] Nenhum conteudo de prontuario aparece em log, exception ou `toString` hoje. **Risco latente**: `Evolucao` e um `record`, cujo `toString()` padrao imprimiria `texto`; se alguem passar a logar o objeto, o prontuario vai para o log. Sobrescrever `toString` em `model/Evolucao.java`.
  - Corrigido em: `model/Evolucao.java` (`toString` oculta o texto)
- `Usuario.toString` (`Usuario.java:53-55`) mostra so nome/CRP; `Paciente.toString` so o nome. Hash/salt/chave nunca sao impressos.
- `log_acesso` guarda apenas acao/entidade/id, sem conteudo (bom).
- Mensagens: `Criptografia.java:102` e generica; `DaoException`/`BackupService` expoem mensagens do driver/caminhos (B1).
- O texto do prontuario permanece num `ListView` (`ui/ProntuarioView.java:38-48`) ate trocar de tela ou sair; sem timeout (M1).

### 3.3 Controle de acesso
- Prontuario: `exigirPsicologo` exige papel e chave (`ProntuarioService.java:59-64`) - correto. Registros de outro psicologo aparecem como `SIGILOSO` (linha 55) - bom.
- Qualquer psicologo le/escreve prontuario de qualquer paciente da clinica (sem vinculo terapeuta-paciente); so o conteudo antigo de outro autor fica protegido.
- `ProntuarioService.gravar` (`:36-45`) nao confere que `atendimentoId` e `retificaId` pertencem ao `pacienteId` informado (a FK so garante existencia): uma retificacao pode apontar para registro de outro paciente. Retificar um registro "SIGILOSO" de outro autor e permitido.
- A secretaria acessa todos os dados cadastrais, a agenda e pode exportar o relatorio LGPD (`PacienteService.java:98-100` so exige sessao). Avaliar restringir a exportacao ao psicologo.
- Servicos/convenios/repasse sem controle de papel (A1); usuarios nao podem ser desativados (A2).

### 3.4 Consentimento
- Historico append-only com "vale o mais recente" (`ConsentimentoDAO`) - boa pratica.
- O unico consentimento exigido pelo codigo e `ATENDIMENTO_ONLINE`, so ao agendar (`AgendaService.java:39-41`); revogar depois nao afeta consultas ja marcadas. `CONTATO_WHATSAPP_EMAIL` nao e verificado em lugar nenhum (o Painel so mostra aviso textual, `PainelView.java:40`). `CONTRATO_TERAPEUTICO` nao e exigido para agendar nem para registrar prontuario.
- O registro de consentimento nao guarda versao do termo nem quem coletou (so o log de auditoria, que registra o usuario logado). Dados de saude podem se apoiar em tutela da saude (LGPD art. 11, II, f) e nao em consentimento; documentar a base legal de cada tratamento.
- Menor de idade: exige `responsavelLegal` preenchido (`PacienteService.java:42-43`), mas nao registra consentimento do responsavel.

### 3.5 Direitos do titular
- Acesso/portabilidade (art. 18, II e V): exportacao TXT existe e e auditada (`PacienteService.java:126`), mas sem formato estruturado, sem cifragem (A5) e sem verificacao de identidade do solicitante. O prontuario fica fora, por decisao documentada.
- Correcao (art. 18, III): editar cadastro e possivel e auditado como `ALTERACAO_PACIENTE`, sem valor anterior/posterior.
- Eliminacao/anonimizacao (art. 18, IV e VI) e termino do tratamento (art. 15-16): **nao implementados** (M8). A decisao de nao ter "excluir" e coerente com a guarda minima de 5 anos, mas falta o procedimento pos-prazo.

### 3.6 Auditoria
- Boa cobertura: login/logout, cadastro/alteracao de paciente, leitura/escrita/retificacao de prontuario, consentimentos, exportacao, backup, troca de senha.
- Lacunas: leitura/pesquisa de cadastros e agenda nao e auditada; alteracoes de servico/convenio/repasse nao sao (A1); falhas de login nao registram a conta tentada (M1); operacao e log nao sao atomicos (M2); eventos de id inexistente sao logados sem efeito (A4); o log e protegido por triggers que quem tem acesso ao arquivo pode remover (sem encadeamento de hash), logo prova pouco contra um administrador malicioso.
- `PrivacidadeView` mostra so os 300 ultimos eventos, sem filtro nem exportacao.

## 4. Pontos positivos

- Criptografia de envelope bem desenhada (DEK aleatoria por psicologo, KEK da senha, troca de senha sem recifrar registros), GCM com IV novo por operacao, comparacao em tempo constante, nenhum segredo hardcoded.
- Prontuario append-only com triggers de UPDATE/DELETE e fluxo de retificacao; log de acesso tambem protegido por triggers.
- `PreparedStatement` em todo o acesso a dados; `VACUUM INTO` com escape de aspas; `PRAGMA foreign_keys = ON` explicito.
- `CHECK` no schema para papel, status, modalidade, `fim > inicio`, `duracao_min > 0`, valores >= 0 e repasse 0..100.
- Mensagem de login generica, zeragem do `char[]` da senha em `entrar`/`trocarSenha`, migracao de esquema transacional e idempotente (`Database.migrar`).
- Validacao de CPF por digitos verificadores e unicidade, CPF opcional (minimizacao), responsavel legal obrigatorio para menores, bloqueio de agendamento online sem consentimento.
- Secretaria sem acesso a chave de prontuario; cada psicologo so decifra os proprios registros.
- Codigo legivel; comentarios explicam o "porque" das decisoes legais e de seguranca.
