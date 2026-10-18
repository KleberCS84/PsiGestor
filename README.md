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

## Como rodar (Windows)

Pré-requisitos: JDK 21 e Maven 3.9+.

```powershell
cd psigestor
mvn clean test        # roda os 16 testes automatizados
mvn javafx:run        # abre o sistema
```

No primeiro acesso, o sistema pede para cadastrar o **psicólogo responsável**.
O banco é criado em `C:\Users\<voce>\PsiGestor\psigestor.db`.

> ⚠️ A chave do prontuário é derivada da senha. **Perdeu a senha = perdeu o acesso aos registros.**

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
consentimento para online, valor do convênio, menor sem responsável, CPF, exportação LGPD, resumo/repasse e backup.

## Próximos sprints (backlog)

1. Troca de senha com recifragem dos registros do prontuário
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
