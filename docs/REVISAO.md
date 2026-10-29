# Revisao de codigo - PsiGestor (src/main/java)

Escopo: todos os arquivos de `src/main/java` e `src/main/resources/db/schema.sql`, lidos integralmente.
Caminhos abaixo relativos a `src/main/java/br/com/kleber/psigestor/` (exceto o schema).
Nada foi executado; tudo o que esta listado vem de leitura do codigo. Itens marcados **(suspeita)** nao foram confirmados.

## 1. Resumo executivo

O projeto tem uma base boa de privacidade: AES-256-GCM com IV aleatorio por registro, chave de dados (DEK) por psicologo embrulhada por uma chave derivada da senha (PBKDF2), prontuario append-only com triggers, trilha de auditoria e nenhuma chave hardcoded. Nao ha SQL injection: nenhum dado digitado e concatenado em SQL (tudo usa `PreparedStatement`); as concatenacoes que existem usam so constantes internas (DDL e `PRAGMA user_version` da migracao em `Database`, nome de coluna em `AtendimentoDAO.contarSobrepostos`) ou caminho de arquivo com escape de aspas (`VACUUM INTO`).

Estado atual (apos as correcoes registradas na secao 2):

1. **Financeiro confiavel**: o parser de numeros foi refeito (C1) e o dinheiro trafega em centavos (`long`) do schema a UI; o percentual de repasse e aplicado com `BigDecimal`/`HALF_EVEN` e a parte da clinica mais o liquido sempre fecham com o bruto (M7, migracao v5).
2. **Autorizacao e auditoria no service**: servicos, convenios e repasse passam por `CadastroService` com papel e auditoria (A1); usuarios podem ser desativados (A2); operacao e log sao atomicos (M2); o status do atendimento segue maquina de estados (A3).
3. **Autenticacao endurecida**: bloqueio progressivo por login e por inatividade (M1), PBKDF2 com 600.000 iteracoes e senha minima de 12 (M5), tempo de resposta igual para login inexistente/inativo e checagem de permissao antes de revelar login duplicado (B5).
4. **LGPD**: backup cifrado `.psgb` com restauracao verificada (A5 parcial, B4), exportacao do titular restrita ao psicologo, anonimizacao apos o prazo de guarda (M8). O banco principal continua sem cifra (protecao em repouso delegada ao BitLocker, ver `docs/POLITICA_DADOS.md`).
5. **Ainda em aberto**: ver secao 4, "Limitacoes conhecidas" (M4, M6, B3, parte de B6, subitens sem [x] de B9 e os itens que a revisao final deixou abertos). A verificacao independente destas correcoes e os achados posteriores estao em `docs/REVISAO_FINAL.md`.

Contagem original: 1 Critica, 5 Altas, 9 Medias, 9 Baixas (total 24).
Situacao: 18 corrigidos, 3 parciais (A5, B6, B9), 3 abertos e documentados como limitacao (M4, M6, B3).

## 2. Achados por severidade

### CRITICA

[x] **C1. Parser de numero destroi valores com ponto decimal**
- Corrigido em: `util/Validadores.java` (`numeroBR`), `ui/CadastrosView.java`, `ui/EquipeView.java`
- `ui/CadastrosView.java:86-92` (`numero`), usado em `CadastrosView.java:40,65` e `ui/EquipeView.java:37,74`.
- Descricao: `s.replace(".", "").replace(",", ".")` trata todo ponto como separador de milhar. Entrada no formato "americano" ou colada de planilha e multiplicada por 10^casas.
- Cenario: cadastrar servico com valor `150.00` grava R$ 15.000,00; convenio `80.50` grava R$ 8.050,00; repasse `7.5` passa na validacao `0..100` do `EquipeView` e grava 75 %. O valor e copiado para cada atendimento (`service/AgendaService.java:57,59`), entao o erro contamina faturado, recebido e repasse sem aviso. Tambem aceita `NaN`/`Infinity`/`1e9` (`Double.parseDouble`), e `NaN` passa em `p < 0 || p > 100` (`EquipeView.java:38`).
- Correcao: usar `NumberFormat.getInstance(Locale.of("pt","BR"))` com `ParsePosition` (rejeitando texto sobrando), ou `BigDecimal` apos normalizar apenas a virgula; rejeitar NaN/Infinity/negativos; limitar maximo razoavel. Idealmente guardar dinheiro em centavos (ver M7).

### ALTA

[x] **A1. Servicos, convenios e repasse sem checagem de sessao/papel nem auditoria**
- Corrigido em: `service/CadastroService.java` (novo), `service/Contexto.java`, `dao/UsuarioDAO.java` (`atualizarRepasse`), `ui/CadastrosView.java`, `ui/EquipeView.java`, `ui/AgendaView.java`, `ui/PacientesView.java`
- `ui/CadastrosView.java:39,64` (`ctx.servicos.salvar`, `ctx.convenios.salvar`), `ui/EquipeView.java:39` (`ctx.usuarios.atualizarRepasse`).
- Descricao: nao existe service para essas operacoes; a UI chama o DAO. `EquipeView` desabilita o botao para secretaria (linha 32), mas `CadastrosView` nao restringe nada, e servico/convenio definem o valor cobrado.
- Cenario: a secretaria altera valor/convenio e nada fica na trilha de auditoria; qualquer outra tela futura (ou codigo) que chame o DAO contorna a regra. Alterar % de repasse (dinheiro entre profissionais) nao e auditado.
- Correcao: criar service com `exigirSessao()`, regra de papel e `auditar(...)`; DAOs nao devem ser acessados pela UI.

[x] **A2. Impossivel desativar/revogar usuario**
- Corrigido em: `service/AutenticacaoService.java` (`desativar`), `dao/UsuarioDAO.java` (`desativar`, `buscar`, `contarPsicologosAtivos`), `ui/EquipeView.java`
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

[x] **A5. Dados cadastrais em texto puro: banco, backup e exportacao** (PARCIAL)
- Corrigido em: `util/Criptografia.java` (`cifrarComSenha`/`decifrarComSenha`, formato `PSGB`), `service/BackupService.java` (backup `.psgb` cifrado com senha de backup), `service/PacienteService.java` (exportacao so psicologo, nome nao previsivel, `CREATE_NEW`), `ui/PrivacidadeView.java`, `ui/SenhaBackupDialog.java` (novo), `ui/PacientesView.java` (aviso para apagar o arquivo apos entregar), `docs/POLITICA_DADOS.md` (secao 3)
- Decisao (por isso parcial): o banco principal `psigestor.db` continua sem cifra e as colunas cadastrais de `paciente` continuam em claro; a protecao em repouso fica a cargo do BitLocker/Criptografia de dispositivo do Windows (recomendacao em `docs/POLITICA_DADOS.md`, secao 3.4). O relatorio do titular continua TXT legivel (vai para o titular). B3 (permissoes) nao foi tratado.
- `service/BackupService.java:35` (`VACUUM INTO`, copia integral nao cifrada), `service/PacienteService.java:98-130` (TXT puro, nome previsivel `dados_paciente_<id>.txt`, sobrescreve silenciosamente), `resources/db/schema.sql:39-52`.
- Descricao: so o conteudo de `evolucao` e cifrado. CPF, nascimento, telefone, e-mail, contato de emergencia, convenio/carteirinha e a agenda (quem consulta com quem e quando, dado de saude por inferencia) ficam legiveis a quem copiar `~/PsiGestor/psigestor.db`. O comentario do `BackupService` (linhas 14-16) reconhece isso; a unica mitigacao e um aviso na tela.
- Cenario: notebook roubado sem BitLocker, pendrive de backup perdido ou relatorio LGPD esquecido em Downloads: vazamento de dados pessoais de pacientes de psicologia.
- Correcao: cifrar backup e exportacao (AES-GCM com senha do usuario) ou usar SQLite cifrado (SQLCipher); restringir permissoes dos arquivos (B3); considerar cifrar colunas sensiveis de `paciente`.

### MEDIA

[x] **M1. Sem limite de tentativas, sem bloqueio por inatividade; sessao nunca expira**
- Corrigido em: `service/AutenticacaoService.java` (bloqueio por login), `service/Contexto.java` (inatividade), `ui/PsiGestorApp.java` (temporizador), `dao/LogAcessoDAO.java`, `model/LogAcesso.java`, `ui/PrivacidadeView.java`, `resources/db/schema.sql` + `util/Database.java` (migracao v3: `log_acesso.detalhe`)
- `service/AutenticacaoService.java:54-71`, `service/Contexto.java:53-69`, `util/Sessao.java`.
- Descricao: o login pode ser tentado indefinidamente; a falha e auditada com `usuario_id = null` e sem o login tentado (linha 60), entao a trilha nao permite identificar a conta atacada. A sessao (com a DEK em memoria) dura ate "Sair".
- Cenario: computador deixado logado em consultorio compartilhado exibe o prontuario a qualquer pessoa (sigilo, Codigo de Etica art. 9).
- Correcao: atraso exponencial/bloqueio temporario por login; temporizador de inatividade que chama `ctx.sair()`; registrar o login tentado no log.
- Como ficou no log: `log_acesso.detalhe` grava `login=<login cadastrado>` (maximo 40 caracteres) quando o login existe, e so `login inexistente` quando nao existe, nunca o texto digitado (pode ser a senha digitada no campo errado).
- Limite conhecido: o contador de tentativas fica em memoria; fechar e reabrir o app zera o bloqueio. Nao protege contra ataque offline ao arquivo `.db`.

[x] **M2. Auditoria nao e atomica com a operacao**
- Corrigido em: `service/Contexto.java` (`transacao`), `service/ProntuarioService.java`, `service/PacienteService.java`, `service/AgendaService.java`, `service/CadastroService.java`, `service/AutenticacaoService.java`, `service/BackupService.java` (`VACUUM INTO` nao roda em transacao: backup sem log e apagado)
- `service/ProntuarioService.java:41-43`, `service/PacienteService.java:48-49`, `service/AgendaService.java:61-62` e demais (nenhuma usa transacao; `Database` mantem `autoCommit=true`).
- Cenario: o registro de prontuario e inserido, depois `auditar` falha (disco cheio/lock): o usuario ve erro, repete e cria registro duplicado que nunca pode ser apagado (triggers); ou o registro existe sem entrada de log. Na leitura, `listar` audita antes de decifrar (`ProntuarioService.java:50`), registrando leitura mesmo que ela falhe.
- Correcao: utilitario `transacao(Runnable)` em `Contexto` (setAutoCommit(false)/commit/rollback) englobando operacao + log.

[x] **M3. Um registro corrompido impede a leitura de todo o prontuario do paciente**
- Corrigido em: `service/ProntuarioService.java` (`listar`, `ilegivel`; evento `REGISTRO_ILEGIVEL` em `log_acesso`)
- `service/ProntuarioService.java:52-56` + `util/Criptografia.java:101-103`.
- Descricao: `decifrar` lanca `RegraNegocioException`; o `stream().map` nao trata, entao um unico registro com tag invalida derruba a lista inteira.
- Correcao: capturar por registro, mostrar "[registro ilegivel #id]" e auditar o incidente.

**M4. Ciphertext sem dados autenticados associados (AAD)**
- `util/Criptografia.java:84-94`, `service/ProntuarioService.java:40-42`.
- Descricao: GCM autentica o conteudo, mas nao o vinculo com `paciente_id`, `profissional_id`, `registrado_em`, `retifica_id` (colunas em claro). Quem puder escrever no `.db` (removendo antes a trigger, o que o arquivo permite) pode mover um registro cifrado de um paciente para outro ou alterar metadados; a decifragem continua valida. Impacto real depende do modelo de ameaca; trate como **(suspeita)** de risco de integridade.
- Correcao: passar `paciente_id|profissional_id|iv` como AAD (`Cipher.updateAAD`).

[x] **M5. PBKDF2-HMAC-SHA256 com 210.000 iteracoes abaixo da recomendacao atual; senha minima fraca**
- Corrigido em: `util/Criptografia.java` (`ITERACOES_ATUAL` = 600.000, iteracoes por parametro), `schema.sql` e `util/Database.java` (v4: `usuario.iteracoes`, existentes = 210000), `dao/UsuarioDAO.java`, `model/Usuario.java`, `service/AutenticacaoService.java` (`SENHA_MINIMA` = 12 para senha nova/trocada; `atualizarIteracoes` no login, mesma transacao, DEK reembrulhada sem recifrar o prontuario). Checagem contra senhas comuns e Argon2id nao foram feitos.
- `util/Criptografia.java:29` (o comentario atribui 210.000 ao SHA-256), `service/AutenticacaoService.java:14`.
- Descricao: pelo que conheco do OWASP Password Storage Cheat Sheet, 210.000 e o valor para PBKDF2-SHA512 e ~600.000 para SHA-256 (confirme na versao vigente). Como a KEK que protege todo o prontuario deriva so da senha, e o arquivo `.db`/backup permite ataque offline ilimitado, a robustez da senha e a unica barreira.
- Correcao: aumentar iteracoes (gravando a contagem por usuario para permitir migracao futura), senha minima 12 ou passphrase, checagem contra senhas comuns; considerar Argon2id se aceitarem dependencia.

**M6. Troca de senha nao rotaciona a DEK**
- `service/AutenticacaoService.java:77-102,110-120`.
- Descricao: so o embrulho muda. Um backup ou copia anterior do `.db` continua desembrulhavel com a senha antiga e entrega a mesma DEK que protege tambem os registros novos. Para usuarios migrados, a DEK e `PBKDF2(senha_original, salt_antigo)` (linha 115), derivavel da senha original para sempre se o salt antigo estiver em algum backup.
- Cenario: senha vazada -> troca de senha -> o vazamento nao e remediado para dados ja copiados.
- Correcao: documentar a limitacao; oferecer "rotacionar chave" (DEK nova para registros novos, DEKs antigas mantidas embrulhadas, versao da chave em cada linha de `evolucao`).

[x] **M7. Dinheiro em `double`**
- Corrigido em: `resources/db/schema.sql` + `util/Database.java` (v5: `convenio.valor_sessao`, `servico.valor`, `atendimento.valor` REAL -> INTEGER com `CAST(ROUND(valor*100) AS INTEGER)`; tabela recriada a partir do `schema.sql`, FKs conferidas com `foreign_key_check`), `model/Servico.java`, `model/Convenio.java`, `model/Atendimento.java` (`valorCentavos`, `long`), `dao/ServicoDAO.java`, `dao/ConvenioDAO.java`, `dao/AtendimentoDAO.java`, `service/FinanceiroService.java` (somas em `long`; `parteClinica` com `BigDecimal` e `RoundingMode.HALF_EVEN`; liquido = bruto - clinica), `service/CadastroService.java`, `service/AgendaService.java`, `util/Validadores.java` (`centavos`), `ui/Ui.java` (`moeda(long)`: unica conversao para texto), `ui/CadastrosView.java`, `ui/AgendaView.java`, `ui/FinanceiroView.java`. O percentual de repasse continua `REAL`/`double` no banco (nao e dinheiro). Teste: 3 x R$ 33,33 = R$ 99,99.
- `model/Atendimento.java:15`, `service/FinanceiroService.java:30-37,64`, `schema.sql` (`REAL`).
- Descricao: somas acumuladas sem arredondar em `resumo` (so `repasse` usa `arred`), com risco de divergencia de centavos entre cards e tabela de repasse.
- Correcao: `BigDecimal` ou inteiro em centavos, com `RoundingMode` definido.

[x] **M8. Direito de eliminacao/anonimizacao nao implementado**
- Corrigido em: `service/PacienteService.java` (`anonimizarPaciente`), `dao/PacienteDAO.java` (`ultimaReferencia`, `anonimizar`), `ui/PacientesView.java` (confirmacao digitando o nome), `docs/POLITICA_DADOS.md` (regra; eliminacao do prontuario = decisao pendente)
- Complemento (REVISAO_FINAL M-4): a anonimizacao limpa tambem `responsavel_legal` e `numero_carteirinha`, arquiva, grava `paciente.anonimizado_em` (migracao v6) e o cadastro passa a recusar edicao, desarquivamento e agendamento; a data de referencia inclui o ultimo registro de prontuario. Ficam `convenio_id` e `consentimento.observacao`.
- `service/PacienteService.java:53-63` (so arquivar), `schema.sql` (FK `ON DELETE RESTRICT`, triggers de delete). Detalhes na secao 3.5.
- Correcao: `anonimizarPaciente(id)` (substitui nome/CPF/contatos por valores neutros mantendo id e vinculos financeiros), restrito a psicologo, apos `criado_em + 5 anos`, com auditoria; politica documentada.

[x] **M9. Datas e validacoes de agenda fracas**
- Corrigido em: `service/AgendaService.java` (janela de datas, `verificarConflitos`), `dao/AtendimentoDAO.java` (`contarConflitosPaciente`), `ui/AgendaView.java` (`montarInicio`, `diaSelecionado`), `service/Contexto.java` (`hoje`)
- `service/AgendaService.java:25,35-37`, `ui/AgendaView.java:33-35,69,129-135`.
- Descricao: aceita datas no passado e em anos absurdos; conflito checado so por profissional, nao por paciente (o mesmo paciente pode ser marcado em horarios sobrepostos); `data.getValue()` null (DatePicker limpo) causa NPE dentro de `executar`, exibido como "Erro inesperado: null"; `dia.getValue()` null em `AgendaView.carregar` (e nos botoes `<`/`>`) lanca NPE fora de `Ui.executar`, na thread JavaFX.
- Correcao: validar `inicio` (janela razoavel; passado so com confirmacao), conflito por paciente, tratar DatePicker nulo.

### BAIXA

[x] **B1. Excecoes engolidas sem log; mensagens internas ao usuario**
- Corrigido em: `util/LogTecnico.java` (novo; `~/PsiGestor/logs/psigestor-N.log`, so classe e pilha, sem mensagens exceto do driver SQL), `ui/Ui.java` (`executar`, `mensagemPara`, `ERRO_GENERICO`), `ui/PsiGestorApp.java` (`DefaultUncaughtExceptionHandler`), `service/BackupService.java`
- `ui/Ui.java:103-113`, `dao/DaoException.java:5`, `service/BackupService.java:40`.
- Nao ha logger nem `printStackTrace` no projeto. `Ui.executar` mostra `"Erro inesperado: " + e.getMessage()` e descarta o stack trace; `DaoException` expoe mensagens do SQLite (ex.: "FOREIGN KEY constraint failed", caminhos). Nao ha `Thread.setDefaultUncaughtExceptionHandler`. Sugestao: `java.util.logging` em arquivo local sem dados de paciente e mensagem generica ao usuario.

[x] **B2. Telas chamam servicos fora de `Ui.executar`**
- Corrigido em: `ui/TelaPrincipal.java` (`item`, `abrirProntuario`), `ui/PainelView.java`, `ui/EquipeView.java`, `ui/PrivacidadeView.java`, `ui/PacientesView.java`, `ui/AgendaView.java`, `ui/ProntuarioView.java`, `ui/CadastrosView.java`, `ui/TelaLogin.java`, `ui/PsiGestorApp.java` (botao Sair)
- `ui/PainelView.java:24-57`, `ui/EquipeView.java:50`, `ui/PrivacidadeView.java:25`, `ui/TelaPrincipal.java:61` (`botoes.get(0).fire()` abre o Painel na criacao da tela principal). Uma `DaoException` ali sobe sem tratamento e a janela pode ficar em branco.

**B3. Arquivos criados com permissoes padrao**
- `ui/PsiGestorApp.java:21-23`, `service/PacienteService.java:123-125`, `service/BackupService.java:31-35`. No Windows herdam a ACL da pasta de destino; considerar restringir a ACL do diretorio `PsiGestor` e dos exports.

[x] **B4. Backup**
- Corrigido em: `service/BackupService.java` (`gerar`: nome com data-hora + sufixo aleatorio, `CREATE_NEW`, `PRAGMA integrity_check` antes de cifrar; `restaurar`: so psicologo, decifra para temporario, `integrity_check`, confere `user_version`, copia o banco atual para `psigestor.db.antes-restauracao-<data>`, substitui e pede reinicio; auditado como `RESTAURACAO_BACKUP` no banco anterior e no restaurado), `util/Database.java` (`arquivo()`, `VERSAO_ESQUEMA` publico), `service/Contexto.java`, `ui/PrivacidadeView.java` (botao "Restaurar backup...")
- Original: nome com precisao de segundos; se o arquivo existir, `VACUUM INTO` falha com mensagem tecnica (`BackupService.java:32,40`). Nao ha verificacao de integridade do arquivo gerado nem rotina de restauracao.

[x] **B5. Enumeracao de usuarios**
- Corrigido em: `service/AutenticacaoService.java` (`entrar`: PBKDF2 com `SALT_FICTICIO` e `ITERACOES_ATUAL` quando o login nao existe ou esta inativo; `cadastrar`: sessao/papel checados antes de qualquer validacao, inclusive do login duplicado). Resta diferenca pequena para usuario ainda com 210.000 iteracoes (migra no proximo login).
- `service/AutenticacaoService.java:59`: usuario inexistente/inativo nao calcula PBKDF2, resposta muito mais rapida que senha errada (enumeracao por tempo). `cadastrar` verifica login duplicado (linha 29) antes de checar a sessao (linha 32). Impacto baixo em app desktop.

**B6. Senha em memoria**
- [x] `PBEKeySpec` nunca chama `clearPassword()` (`util/Criptografia.java:108`), entao a copia interna do `char[]` permanece.
  - Corrigido em: `util/Criptografia.java` (`pbkdf2`)
- A UI converte `String` -> `char[]` (`ui/TelaLogin.java:41`, `TrocaSenhaDialog.java:47`) e a `String` imutavel nao e zerada. `AutenticacaoService.cadastrar` nao zera a senha (so `entrar` e `trocarSenha` zeram). A `SecretKeySpec` da DEK permanece na `Sessao` apos `sair()` ate o GC. Mitigacao parcial; documentar.

[x] **B7. Validacoes de paciente incompletas**
- Corrigido em: `service/PacienteService.java` (`salvar`: nulo, limites de tamanho, telefone, nascimento ate 130 anos, convenio inexistente), `util/Validadores.java` (`telefoneValido`), `ui/PacientesView.java` (`rascunho`: formulario edita uma copia)
- `service/PacienteService.java:25-51`: sem limite de tamanho de campos, telefone nao validado, `p == null` gera NPE, data de nascimento muito antiga aceita, `convenioId` inexistente cai em `DaoException` com mensagem de FK. A UI muta o objeto `Paciente` da tabela antes de validar (`ui/PacientesView.java:111-119`); se a validacao falha, a linha exibe dados nao salvos ate recarregar.

[x] **B8. Entradas sem validacao em consentimento/pagamento; leituras sem sessao**
- Corrigido em: `service/PacienteService.java` (`registrarConsentimento`, `aniversariantes`), `service/AgendaService.java` (`registrarPagamento`, `FORMAS_PAGAMENTO`, `doDia`, `periodo`), `ui/AgendaView.java`
- `service/PacienteService.java:78-82` (`tipo == null` -> NPE; paciente inexistente -> `DaoException` de FK), `service/AgendaService.java:72-76` (forma de pagamento livre/nula; pagar atendimento cancelado ou ja pago sobrescreve a forma; pagamento so e contabilizado para REALIZADO, `FinanceiroService.java:34-37`). `PacienteService.aniversariantes` (linha 74) e `AgendaService.doDia/periodo` (85-91) nao chamam `exigirSessao()`.

**B9. Detalhes menores** (PARCIAL)
- [x] `dao/PacienteDAO.java:65-69`: `LIKE` sem escapar `%`/`_` digitados.
  - Corrigido em: `dao/PacienteDAO.java` (`LIKE ? ESCAPE '\'`, `escaparLike`)
- [x] `dao/AtendimentoDAO.java:76`, `dao/PacienteDAO.java:59`, `dao/UsuarioDAO.java:89`, `util/Database.java:59`: `rs.getInt(1)` sem `rs.next()` so funciona porque o sqlite-jdbc pre-posiciona o cursor; nao e portavel.
  - Corrigido em: `dao/AtendimentoDAO.java` (`contarSobrepostos`), `dao/PacienteDAO.java` (`cpfEmUso`), `dao/UsuarioDAO.java` (`contar`), `util/Database.java` (`migrar`)
- Conexao JDBC unica e nao sincronizada (`util/Database.java:30`): segura enquanto tudo roda na thread JavaFX; qualquer uso futuro de `Task`/thread de fundo (ex.: backup, PBKDF2 sem congelar a UI) causara corrida.
- `dao/ConsentimentoDAO.java:47-53`: `vigente` carrega todo o historico a cada checagem; `ServicoDAO.buscar` lista tudo e filtra em memoria (irrelevante nesta escala).
- [x] `convenio.prazo_pagamento_dias` sem `CHECK` (aceita negativo).
  - Corrigido em: `resources/db/schema.sql` (CHECK), `util/Database.java` (migracao v2 com triggers para bancos existentes)

## 3. LGPD e prontuario (secao especifica)

### 3.1 Criptografia (verificado)
| Item | Situacao |
|---|---|
| Algoritmo/modo | AES-256-GCM (`AES/GCM/NoPadding`), tag de 128 bits - adequado (`util/Criptografia.java`, `cifrarBytes`/`decifrarBytes`). |
| IV | 12 bytes de `SecureRandom` novo a cada cifragem, gravado junto do texto (`Criptografia.cifrarBytes`). Aceitavel. |
| Chave | DEK aleatoria de 256 bits por psicologo (`Criptografia.novaChaveDados`), embrulhada por KEK = PBKDF2(senha, salt proprio) (`AutenticacaoService.embrulhar`). **Nenhuma chave hardcoded** (nao ha constantes de chave/segredo no codigo). |
| Senha | PBKDF2-HMAC-SHA256, salt de 16 bytes, comparacao em tempo constante (`Criptografia.hashSenha`/`senhaConfere`). 600.000 iteracoes, contagem gravada por usuario e migrada no login; senha minima de 12 (M5). Login inexistente/inativo tambem paga o PBKDF2 (B5). |
| Backup | Arquivo `.psgb` cifrado com AES-GCM e chave derivada de uma senha de backup (A5, B4); formato v2 grava as iteracoes no cabecalho autenticado e ainda le a v1 (`Criptografia.cifrarComSenha`/`decifrarComSenha`). |
| AAD | Nao usado (M4, ver secao 4). |
| Recuperacao | Codigo de recuperacao do psicologo (128 bits, mostrado uma vez): segundo embrulho da DEK por PBKDF2 do codigo (`usuario.dek_recuperacao`, migracao v7); "Esqueci a senha" reembrulha a DEK com a senha nova e troca o codigo, em uma transacao, com o mesmo bloqueio do login (`AutenticacaoService.recuperarAcesso`, `util/CodigoRecuperacao`). Secretaria: senha redefinida por psicologo. Risco restante: perder senha e codigo; codigo vazado = acesso ao prontuario. Ver `docs/POLITICA_DADOS.md` secao 4. |
| Metadados | `paciente_id`, `profissional_id`, `atendimento_id`, `registrado_em`, `retifica_id` ficam em claro (revelam frequencia e cronologia). |

### 3.2 Vazamento em logs, toString e mensagens
- [x] Nenhum conteudo de prontuario aparece em log, exception ou `toString` hoje. **Risco latente**: `Evolucao` e um `record`, cujo `toString()` padrao imprimiria `texto`; se alguem passar a logar o objeto, o prontuario vai para o log. Sobrescrever `toString` em `model/Evolucao.java`.
  - Corrigido em: `model/Evolucao.java` (`toString` oculta o texto)
- `Usuario.toString` mostra so nome/CRP; `Paciente.toString` so o nome. Hash/salt/chave nunca sao impressos.
- `log_acesso` guarda apenas acao/entidade/id, sem conteudo (bom).
- Mensagens: a falha de decifragem (`Criptografia.decifrarBytes`) e generica; erros inesperados mostram mensagem generica ao usuario e vao para `~/PsiGestor/logs` so com classe e pilha (B1 corrigido).
- O texto do prontuario permanece no `ListView` de `ui/ProntuarioView.java` ate trocar de tela ou sair; o bloqueio por inatividade encerra a sessao (M1 corrigido).

### 3.3 Controle de acesso
- Prontuario: `ProntuarioService.exigirPsicologo` exige papel e chave - correto. Registros de outro psicologo aparecem como `ProntuarioService.SIGILOSO` em `listar` - bom.
- Qualquer psicologo le/escreve prontuario de qualquer paciente da clinica (sem vinculo terapeuta-paciente); so o conteudo antigo de outro autor fica protegido.
- `ProntuarioService.gravar` nao confere que `atendimentoId` e `retificaId` pertencem ao `pacienteId` informado (a FK so garante existencia): uma retificacao pode apontar para registro de outro paciente. Retificar um registro "SIGILOSO" de outro autor e permitido.
- A secretaria acessa todos os dados cadastrais e a agenda; a exportacao do relatorio LGPD agora e restrita ao psicologo (A5).
- Servicos/convenios/repasse exigem papel de psicologo e sao auditados (A1 corrigido); usuarios podem ser desativados, nunca o ultimo psicologo ativo (A2 corrigido), e reativados por um psicologo (REVISAO_FINAL B-1).
- Leituras de paciente, consentimentos e da trilha de auditoria exigem sessao; a tela Privacidade le o log por `AutenticacaoService.trilhaDeAcesso`, nao pelo DAO (REVISAO_FINAL B-5).
- Cadastro de usuario confere sessao e papel antes de qualquer outra validacao (B5).

### 3.4 Consentimento
- Historico append-only com "vale o mais recente" (`ConsentimentoDAO`) - boa pratica.
- O unico consentimento exigido pelo codigo e `ATENDIMENTO_ONLINE`, so ao agendar (`AgendaService.agendar`); revogar depois nao afeta consultas ja marcadas. `CONTATO_WHATSAPP_EMAIL` nao e verificado em lugar nenhum (o Painel so mostra aviso textual, `ui/PainelView.java`). `CONTRATO_TERAPEUTICO` nao e exigido para agendar nem para registrar prontuario.
- O registro de consentimento nao guarda versao do termo nem quem coletou (so o log de auditoria, que registra o usuario logado). Dados de saude podem se apoiar em tutela da saude (LGPD art. 11, II, f) e nao em consentimento; documentar a base legal de cada tratamento.
- Menor de idade: exige `responsavelLegal` preenchido (`PacienteService.salvar`), mas nao registra consentimento do responsavel.

### 3.5 Direitos do titular
- Acesso/portabilidade (art. 18, II e V): exportacao TXT existe e e auditada, so pelo psicologo, com nome nao previsivel e sem sobrescrever (A5). Continua sem formato estruturado, em texto legivel (vai para o titular) e sem verificacao de identidade do solicitante. O prontuario fica fora, por decisao documentada.
- Correcao (art. 18, III): editar cadastro e possivel e auditado como `ALTERACAO_PACIENTE`, sem valor anterior/posterior.
- Eliminacao/anonimizacao (art. 18, IV e VI) e termino do tratamento (art. 15-16): anonimizacao implementada apos o prazo de guarda (5 anos do mais recente entre ultimo atendimento, ultimo registro de prontuario e cadastro), restrita ao psicologo e auditada (M8). Limpa nome, CPF, nascimento, contatos, responsavel legal e carteirinha; arquiva o paciente e impede editar, desarquivar ou agendar de novo (REVISAO_FINAL M-4). Mantem `convenio_id` (financeiro) e o texto livre `consentimento.observacao`. A eliminacao do prontuario segue como decisao pendente em `docs/POLITICA_DADOS.md`.

### 3.6 Auditoria
- Boa cobertura: login/logout (`LOGIN`, `LOGOUT`, `LOGOUT_INATIVIDADE`), falhas e bloqueio de login (`LOGIN_FALHOU`, `LOGIN_BLOQUEADO`), cadastro/alteracao/anonimizacao de paciente, leitura/escrita/retificacao de prontuario, consentimentos, exportacao, backup e restauracao, troca de senha, desativacao/reativacao de usuario (`DESATIVACAO_USUARIO`, `REATIVACAO_USUARIO`), preferencia de inatividade (`PREFERENCIA_ALTERADA`, detalhe "inatividade: 15 -> 30") e edicao de agendamento (`ALTERACAO_ATENDIMENTO`, detalhe com data/hora e servico antes e depois, sem nome de paciente).
- Fechadas: alteracoes de servico/convenio/repasse auditadas (A1); falhas de login registram a conta cadastrada, nunca o texto digitado (M1); operacao e log atomicos (M2); id inexistente nao gera evento (A4); restauracao de backup auditada (B4).
- Excecao de proposito: o logout (manual ou por inatividade) encerra a sessao primeiro e so depois tenta gravar o log; se o log falhar, o evento vai so para o log tecnico (REVISAO_FINAL M-1). Sigilo vem antes da trilha.
- Lacunas restantes: leitura/pesquisa de cadastros e agenda nao e auditada; o log e protegido por triggers que quem tem acesso ao arquivo pode remover (sem encadeamento de hash), logo prova pouco contra um administrador malicioso.
- `PrivacidadeView` mostra so os 300 ultimos eventos, sem filtro nem exportacao.

## 4. Limitacoes conhecidas

Itens avaliados e mantidos em aberto de proposito; o risco de cada um:

- **M4. Ciphertext sem AAD**: quem consegue escrever no `.db` (removendo antes as triggers) pode mover um registro cifrado de prontuario para outro paciente ou alterar seus metadados sem que a decifragem acuse adulteracao.
- **M6. Troca de senha nao rotaciona a DEK**: uma copia antiga do `.db` ou um backup feito antes da troca continua abrindo todo o prontuario, inclusive os registros novos, com a senha antiga vazada.
- **B3. Arquivos com permissoes padrao**: banco, backups, logs e exportacoes herdam a ACL da pasta, entao outra conta do Windows com acesso a pasta pode copia-los.
- **B6 (restante). Senha em memoria**: a `String` digitada na UI e a senha usada em `cadastrar` nao sao zeradas, e a DEK fica na memoria ate o coletor de lixo, entao um dump de memoria do processo pode expo-las.

Apontados em `docs/REVISAO_FINAL.md` e ainda abertos (total ou parcialmente):

- **Copia `antes-restauracao` em claro (M-3, parcial)**: os temporarios de backup/restauracao ja sao apagados na partida e o dialogo mostra onde a copia ficou, mas a copia do banco anterior continua sem cifra ate alguem apaga-la, legivel para quem acessar a pasta.
- **Contador de tentativas de login em memoria (M1)**: fechar e reabrir o PsiGestor zera o bloqueio, entao quem tem acesso ao computador pode continuar adivinhando senhas em rodadas de 5 tentativas.
- **Restaurar backup antigo reativa acessos e desfaz anonimizacoes (M-7, so aviso)**: o aviso orienta revisar a Equipe e refazer as anonimizacoes, mas se ninguem fizer isso um ex-colaborador volta a entrar e dados pessoais eliminados voltam a existir.
- **Percentual de repasse anterior a v6 (M-5)**: atendimentos realizados antes da migracao v6 receberam o percentual vigente no dia da migracao, entao relatorios de periodos em que o percentual era outro podem nao bater com o que foi pago.
- **B-7. Status e recibo frageis**: `AGENDADO -> REALIZADO` vale para sessao futura e `marcarReciboEmitido` aceita atendimento cancelado, nao pago ou de convenio, entao o faturado e os recibos pendentes podem incluir o que nao aconteceu.
- **B-8. Exportacao do titular incompleta**: o relatorio nao traz convenio/carteirinha nem valores e pagamentos e pode mostrar caminho de arquivo em erro, entao a resposta ao art. 18, II pode ficar incompleta.
- **B-9. PBKDF2 na thread da tela**: login, backup e restauracao congelam a janela por 1-3 s, e mover isso para uma thread de fundo sem rever a conexao JDBC unica causaria corrida no banco.
- **B-11. Limitador de login sem decaimento**: o contador por login nunca zera com o tempo e guarda em memoria cada texto distinto digitado no campo de login, entao erros esparsos levam a bloqueio e uma senha digitada no campo errado fica na memoria do processo.
- **B-12. Atividade em dialogos e expiracao**: um dialogo longo (novo usuario, backup) nao renova a sessao e, no instante da expiracao, algumas telas mostram "erro generico" em vez de "sessao encerrada", o que confunde o usuario e pode encerrar um cadastro no meio.

## 5. Pontos positivos

- Criptografia de envelope bem desenhada (DEK aleatoria por psicologo, KEK da senha, troca de senha sem recifrar registros), GCM com IV novo por operacao, comparacao em tempo constante, nenhum segredo hardcoded.
- Prontuario append-only com triggers de UPDATE/DELETE e fluxo de retificacao; log de acesso tambem protegido por triggers.
- `PreparedStatement` em todo o acesso a dados; nenhum dado digitado concatenado em SQL (so constantes internas e o caminho do `VACUUM INTO`, com escape de aspas); `PRAGMA foreign_keys = ON` explicito.
- `CHECK` no schema para papel, status, modalidade, `fim > inicio`, `duracao_min > 0`, valores >= 0 e repasse 0..100.
- Mensagem de login generica, zeragem do `char[]` da senha em `entrar`/`trocarSenha`, migracao de esquema transacional e idempotente (`Database.migrar`).
- Validacao de CPF por digitos verificadores e unicidade, CPF opcional (minimizacao), responsavel legal obrigatorio para menores, bloqueio de agendamento online sem consentimento.
- Secretaria sem acesso a chave de prontuario; cada psicologo so decifra os proprios registros.
- Codigo legivel; comentarios explicam o "porque" das decisoes legais e de seguranca.
