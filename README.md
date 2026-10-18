# PsiGestor

Sistema desktop para **consultórios de psicologia**: agenda, pacientes, prontuário com sigilo por padrão,
financeiro com repasse, serviços/convênios e controles de LGPD.

**Stack (disciplinas de Ciência da Computação):** Java 21 (POO) · JavaFX (interface) · SQLite + JDBC/SQL puro (Banco de Dados) · JUnit 5 · Maven

![Painel](docs/img/1_painel.png)

## Funcionalidades (MVP)

| Módulo | O que faz |
|---|---|
| **Painel** | Atendimentos do dia, aguardando, aniversariantes, faturamento do mês, taxa de faltas, alerta de recibos pendentes |
| **Agenda** | Agendar com checagem de conflito de horário, status (confirmado/realizado/faltou/cancelado), pagamento, recibo, atalho para o prontuário |
| **Pacientes** | Cadastro com validação de CPF, responsável legal para menores, consentimentos com histórico, arquivar (sem excluir), exportação LGPD |
| **Prontuário** | Registros cifrados (AES-256-GCM) com a senha do psicólogo; não podem ser editados nem apagados — só retificados |
| **Financeiro** | Faturado, recebido, a receber, faltas, repasse por profissional |
| **Serviços & Convênios** | Serviços com código TUSS opcional; convênios com registro ANS e valor por sessão |
| **Equipe & Repasse** | Usuários (psicólogo/secretária) e % retido pela clínica |
| **Privacidade** | Log de acesso imutável, backup, checklist legal |

Os pontos legais estão em **[docs/PONTOS_DE_ATENCAO_LEGAIS.md](docs/PONTOS_DE_ATENCAO_LEGAIS.md)** e a modelagem do banco em **[docs/MODELAGEM.md](docs/MODELAGEM.md)**.

### Detalhes do que o código implementa

- **Perfis:** `PSICOLOGO` e `SECRETARIA`. Login com senha armazenada apenas como hash PBKDF2 + salt.
- **Prontuário:** `ProntuarioService` registra evoluções cifradas com a chave do psicólogo (AES-256-GCM, IV novo a cada
  registro). Triggers do SQLite bloqueiam `UPDATE` e `DELETE` em `evolucao`; correções são feitas por **retificação**
  (novo registro apontando para o original). Registros de outro profissional aparecem como sigilosos e a secretária não
  tem acesso.
- **Agenda:** `AgendaService` checa conflito de horário, altera status, registra forma de pagamento e emissão de recibo;
  cancelamento libera o horário.
- **Pacientes:** `PacienteService` valida CPF e exige responsável legal para menores; paciente é arquivado, nunca excluído.
- **Financeiro:** `FinanceiroService` calcula faturado, recebido, a receber, taxa de faltas e repasse por profissional
  (percentual retido pela clínica).
- **LGPD:** consentimentos com histórico (`CONTRATO_TERAPEUTICO`, `CONTATO_WHATSAPP_EMAIL`, `ATENDIMENTO_ONLINE`);
  exportação dos dados do titular em TXT (art. 18, sem o conteúdo do prontuário); log de acesso imutável (triggers
  bloqueiam alteração); backup consistente via `VACUUM INTO` (restrito ao psicólogo; o prontuário segue cifrado, mas os
  dados cadastrais **não**, então guarde o backup em local protegido).

## Pré-requisitos

| Item | Versão | Observação |
|---|---|---|
| JDK | 21 (`maven.compiler.release` = 21) | Qualquer distribuição (Temurin, Oracle etc.) |
| Maven | 3.9+ | Baixa JavaFX 21.0.5, sqlite-jdbc 3.46.1.3 e JUnit 5.11.3 automaticamente |
| Banco de dados | SQLite embutido | Nada para instalar: o driver `sqlite-jdbc` cria o arquivo `.db` sozinho |

Não há servidor, perfis Maven nem variáveis de ambiente a configurar.

## Instalação e configuração

```powershell
git clone <url-do-repositorio>
cd psigestor
```

- **Banco de dados:** criado automaticamente em `<pasta do usuário>\PsiGestor\psigestor.db`
  (ex.: `C:\Users\<voce>\PsiGestor\psigestor.db`, via `user.home`). O esquema (`resources/db/schema.sql`) é aplicado na
  abertura e há migração por `PRAGMA user_version`. Arquivos `*.db` estão no `.gitignore`.
- **Chaves de cifragem do prontuário:** não existe chave em arquivo, variável de ambiente ou código. Cada psicólogo
  recebe uma chave de dados aleatória (AES-256) gravada no banco **cifrada** por uma chave derivada da senha dele
  (PBKDF2-HMAC-SHA256, 210.000 iterações, criptografia de envelope). Basta definir a senha (mínimo de 8 caracteres) no
  primeiro acesso, quando o sistema pede o cadastro do **psicólogo responsável**.

> ⚠️ Dá para **trocar** a senha (menu lateral → Trocar senha) sem perder nada, mas **perdeu a senha = perdeu o acesso
> aos registros do prontuário.** Nem a secretária nem quem copiar o `.db` consegue lê-los.

## Como rodar

```powershell
mvn javafx:run        # abre o sistema (classe principal: br.com.kleber.psigestor.ui.PsiGestorApp)
```

Aplicação desktop JavaFX: não usa porta de rede. `ui.Launcher` é um ponto de entrada alternativo (classe que não estende
`Application`), útil para empacotar em um `.jar` único.

## Como rodar os testes

```powershell
mvn clean test        # JUnit 5 (maven-surefire-plugin 3.5.2); 49 testes em 6 classes, SQLite em memória
```

## Estrutura de pastas

```
psigestor/
├── pom.xml
├── docs/                      MODELAGEM.md, PONTOS_DE_ATENCAO_LEGAIS.md, contexto do projeto e capturas (img/)
└── src/
    ├── main/java/br/com/kleber/psigestor/
    │   ├── ui/                JavaFX (PsiGestorApp, Launcher, telas)
    │   ├── service/           regras de negócio + Contexto
    │   ├── dao/               JDBC / SQL puro
    │   ├── model/             entidades, enums, records
    │   └── util/              Database, Criptografia, Validadores, Sessao
    ├── main/resources/        db/schema.sql, ui/estilo.css
    └── test/java/.../         service/ (PsiGestorTest, FinanceiroResumoTest), model/, util/
```

## Arquitetura (camadas)

```
ui/        JavaFX — telas montadas em código (sem FXML), estilo em resources/ui/estilo.css
service/   regras de negócio (agenda, prontuário, financeiro, LGPD) + Contexto (injeção manual)
dao/       acesso a dados com JDBC e SQL puro (PreparedStatement)
model/     entidades (classes encapsuladas, enums, records)
util/      Database, Criptografia, Validadores, Sessao
resources/db/schema.sql   esquema físico com CHECK, FK e triggers
```

Conceitos de POO aplicados: encapsulamento (entidades com getters/setters e validação no service),
enums com comportamento (`StatusAtendimento.ocupaHorario()`), records para objetos imutáveis
(`Evolucao`, `Sessao`, `Resumo`), exceção própria (`RegraNegocioException`), composição (`Contexto`).

## Testes

`PsiGestorTest` cobre: senha com hash, prontuário cifrado no banco, bloqueio de UPDATE/DELETE por trigger,
retificação, secretária sem acesso, sigilo entre psicólogos, conflito de agenda, cancelamento liberando horário,
troca de senha mantendo o prontuário legível, migração de banco antigo, consentimento para online, valor do convênio, menor sem responsável, CPF, exportação LGPD, resumo/repasse e backup (19 testes).

Testes unitários (30 testes):

| Classe de teste | Testes | Cobre |
|---|---|---|
| `util/CriptografiaTest` | 11 | AES-GCM ida e volta, IV aleatório, chave errada e dado adulterado recusados, hash/salt de senha, envelope da chave de dados |
| `util/ValidadoresTest` | 6 | CPF válido/inválido, `somenteDigitos`, `vazio` |
| `model/AtendimentoTest` | 6 | conflito de horário, valores padrão, só `CANCELADO` libera horário |
| `model/PacienteTest` | 4 | idade, maioridade, aniversário, nascimento nulo |
| `service/FinanceiroResumoTest` | 3 | `taxaFaltas` |

Total: 49 testes (`mvn clean test`).

## Próximos sprints (backlog)

1. ~~Troca de senha~~ ✅ (criptografia de envelope)
2. Edição/inativação de serviços, convênios e usuários
3. Instalador Windows com `jpackage` (sem precisar de Maven)
4. Relatórios em PDF (agenda do dia, extrato de repasse)
5. Lembretes por WhatsApp/e-mail **apenas** para quem consentiu, com texto neutro
6. Documentos psicológicos conforme Res. CFP 06/2019

## Telas

| | |
|---|---|
| ![Agenda](docs/img/2_agenda.png) | ![Prontuário](docs/img/4_prontuario.png) |
| ![Financeiro](docs/img/5_financeiro.png) | ![Privacidade](docs/img/8_privacidade.png) |

*Dados das imagens são fictícios.*
