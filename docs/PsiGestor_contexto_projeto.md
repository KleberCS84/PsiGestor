# PsiGestor — Contexto do projeto (para continuar em nova conversa)

## Quem sou
Kleber, estudante de Ciência da Computação (IFG). Estudo POO com Java e Banco de Dados I. Prefiro sprints curtos, exemplos práticos e entender cada decisão de design, não só receber código pronto. Respostas em português, objetivas, com passos acionáveis.

## Ambiente
- Windows 11, JDK 21 (Temurin), Maven 3.9, Eclipse/Android Studio
- Projeto em: `C:\Users\kleber01\Downloads\PsiGestor\psigestor`
- Banco de dados local: `C:\Users\kleber01\PsiGestor\psigestor.db` (apague para recomeçar do zero)
- Comandos: `mvn clean test` (19 testes) · `mvn javafx:run` (abre o sistema)
- GitHub: KleberCS84 (o push eu faço manualmente)

## O que é
Sistema **desktop** para consultórios de psicologia. Funcionalidades inspiradas no TproSystem (vivance.net.br), mas sem copiar marca, layout, código ou dados.

**Stack:** Java 21 · JavaFX 21 (telas montadas em código, sem FXML, com CSS) · SQLite + JDBC com SQL puro · JUnit 5 · Maven

## Status atual — MVP concluído ✅
- `mvn clean test` → **16/16 testes passando, BUILD SUCCESS** na minha máquina (MVP)
- **Sprint 1 (troca de senha):** 19/19 testes; botão "Trocar senha" no menu lateral
- `mvn javafx:run` → sistema abre e funciona; roteiro de teste manual feito, tudo ok
- Ainda não publicado no GitHub

## Arquitetura (pacote `br.com.kleber.psigestor`)
```
model/    Usuario, Papel, Paciente, Convenio, Servico, Atendimento, StatusAtendimento,
          Modalidade, TipoConsentimento, Evolucao (record), Consentimento (record), LogAcesso (record)
dao/      UsuarioDAO, PacienteDAO, ConvenioDAO, ServicoDAO, AtendimentoDAO, EvolucaoDAO,
          ConsentimentoDAO, LogAcessoDAO, JdbcUtil, DaoException
service/  Contexto (injeção de dependências manual + sessão), AutenticacaoService, PacienteService,
          AgendaService, ProntuarioService, FinanceiroService, BackupService
util/     Database (conexão + PRAGMA foreign_keys + carrega schema.sql), Criptografia,
          Validadores (CPF), Sessao (record), RegraNegocioException
ui/       PsiGestorApp, Launcher, Ui (helpers), TelaLogin, TelaPrincipal (menu lateral),
          PainelView, AgendaView, PacientesView, ProntuarioView, FinanceiroView,
          CadastrosView, EquipeView, PrivacidadeView
resources/db/schema.sql   CHECK, FK (RESTRICT / SET NULL), UNIQUE, triggers
resources/ui/estilo.css   tema verde-água
docs/     PONTOS_DE_ATENCAO_LEGAIS.md, MODELAGEM.md (cardinalidade mín,máx + mermaid), img/
```

## Telas / módulos
Painel · Agenda · Pacientes · Prontuário · Financeiro · Serviços & Convênios · Equipe & Repasse · Privacidade (LGPD)

## Regras de negócio implementadas
- **Login:** senha guardada com hash PBKDF2-SHA256 (210 mil iterações + salt). O primeiro usuário cadastrado precisa ser psicólogo; depois disso, só um psicólogo cadastra outros usuários.
- **Prontuário:**
  - O texto é cifrado com AES-256-GCM, usando uma chave derivada da senha do psicólogo.
  - Só o psicólogo autor consegue ler; para outro psicólogo aparece "[conteúdo sigiloso]", e a secretária não tem acesso.
  - Não dá para editar nem apagar: triggers no banco bloqueiam UPDATE e DELETE. Correção é feita por **retificação** (um novo registro com `retifica_id`).
  - **Criptografia de envelope (Sprint 1):** cada psicólogo tem uma chave de dados (DEK) que cifra os registros; a senha só gera a chave de embrulho (KEK) que cifra a DEK (`usuario.chave_cifrada`/`chave_iv`). Trocar a senha regrava só o embrulho, sem recifrar o prontuário. Usuário antigo é migrado no primeiro login.
  - ⚠️ Se a senha for perdida, os registros não podem ser recuperados.
- **Agenda:**
  - Bloqueia conflito de horário do mesmo profissional (sobreposição de intervalos; atendimentos cancelados não contam).
  - Atendimento ONLINE só pode ser agendado com o consentimento correspondente registrado.
  - O valor vem do convênio do paciente ou, se particular, do serviço.
- **Pacientes:**
  - Nome é obrigatório; CPF é opcional, mas se informado precisa ser válido e não repetido.
  - Menor de 18 anos exige responsável legal.
  - Não existe excluir, só **arquivar** (guarda mínima de 5 anos).
  - Exportação dos dados do paciente em TXT (direito do titular pela LGPD).
- **Consentimentos:** guardados como histórico (nunca sobrescreve); vale o registro mais recente.
- **Financeiro:** faturado, recebido, a receber, faltas, recibos pendentes e repasse por profissional (% retido pela clínica).
- **Log de acesso:** imutável (triggers). Backup feito com `VACUUM INTO`.

## Pontos legais considerados (detalhes em docs/PONTOS_DE_ATENCAO_LEGAIS.md)
- **LGPD (Lei 13.709/2018):**
  - Dados de saúde são dados sensíveis (art. 11); base legal: tutela da saúde (art. 11, II, "f").
  - Direitos do titular (art. 18), segurança (art. 46) e comunicação de incidentes (art. 48).
- **CFP:**
  - Código de Ética, art. 9º (sigilo) e art. 8º (atendimento de menores).
  - Res. 01/2009: registro documental obrigatório, guarda mínima de 5 anos.
  - Res. 06/2019: documentos psicológicos (fora do MVP).
  - Res. 11/2018: atendimento online exige cadastro no e-Psi.
  - SATEPSI: testes psicológicos são de uso exclusivo do psicólogo e não foram incluídos.
- **Fiscal:** o recibo de pessoa física sai pelo **Receita Saúde** (obrigatório desde 2025); o sistema não emite, só controla quais estão pendentes.
- **Convênios (ANS):** o código TUSS é digitado pelo usuário, sem tabelas de terceiros embutidas (a CBHPM tem direitos autorais); faturamento TISS em XML ficou fora do MVP.
- **Ficou fora de propósito:** IA sobre o prontuário, envio automático de WhatsApp e cópia de marca/layout/dados do sistema de referência.

## Detalhes técnicos que já deram problema
- sqlite-jdbc não suporta `getGeneratedKeys()` → uso `SELECT last_insert_rowid()` em `JdbcUtil.ultimoId`
- O `Database` divide o `schema.sql` assim: comando comum termina em `;`; trigger termina em `END;`
- Sem `PRAGMA foreign_keys = ON`, o SQLite ignora as chaves estrangeiras
- `CREATE TABLE IF NOT EXISTS` não adiciona coluna em banco existente → mudanças de estrutura viram migração em `Database.migrar()` controlada por `PRAGMA user_version` (versão atual: 1)
- O zip extrai para uma subpasta `psigestor` → é preciso entrar nela antes de rodar o `mvn`

## Backlog (próximos sprints)
1. ~~Troca de senha~~ ✅ feito no Sprint 1 (criptografia de envelope, sem recifrar o prontuário)
2. Instalador Windows com `jpackage`
3. Editar e inativar serviços, convênios e usuários (CRUD completo)
4. Relatórios em PDF (agenda do dia, extrato de repasse)
5. Lembretes por WhatsApp/e-mail só para quem consentiu, com texto neutro
6. Documentos psicológicos conforme Res. CFP 06/2019
7. Publicar no GitHub (KleberCS84/PsiGestor)

## Como quero trabalhar
Um sprint por vez, com entregável testável (`mvn clean test` + teste manual), explicando o porquê de cada decisão de design (POO e banco de dados).
