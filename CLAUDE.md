# PsiGestor

Aplicativo desktop (Java 21, JavaFX, SQLite) para gestão de consultório de psicologia. Documentação em `docs/`.

## Build e testes

- `mvn clean test`: compila e roda toda a suíte (os testes usam `jdbc:sqlite::memory:`).
- `mvn -P instalador clean package`: gera o instalador Windows (jpackage, com Java e JavaFX embutidos).
- O instalador sai em `target/instalador/PsiGestor-<versão>.exe`. Exige JDK 21 e WiX Toolset 3.x no `PATH`.
- A versão vem do `<version>` do `pom.xml` e precisa ser numérica (sem `-SNAPSHOT`).
- Nunca troque o `--win-upgrade-uuid` do `pom.xml`: é ele que faz a versão nova substituir a antiga.
- Passo a passo e checklist de release em `docs/BUILD.md`.

## Camadas (`src/main/java/br/com/kleber/psigestor`)

- `ui/`: telas JavaFX (`Launcher` é a entrada). Chamam os services via `Contexto`, não os DAOs.
- `service/`: regras de negócio. Erro esperado sai como `RegraNegocioException`, com mensagem para o usuário.
- `dao/`: acesso JDBC ao SQLite, um DAO por tabela.
- `model/`: entidades e enums do domínio.
- `util/`: infraestrutura (`Database`, criptografia, validadores, `LogTecnico`, `Sessao`).
- `Contexto` (em `service/`): cria DAOs e services uma única vez, guarda a sessão do usuário logado e faz a injeção de dependência à mão. Cada service recebe o `Contexto` no construtor.
- A conexão JDBC é única, aberta pelo `Database`. O prontuário é cifrado (criptografia de envelope).

## Migrações do banco

- A versão do esquema é `Database.VERSAO_ESQUEMA` e fica dentro do `.db` (`PRAGMA user_version`).
- `schema.sql` usa `CREATE TABLE IF NOT EXISTS` e **não** acrescenta colunas a banco existente. Toda mudança de estrutura vira um passo `if (versao < N)` em `Database.migrar()`, com a linha correspondente no Javadoc de `VERSAO_ESQUEMA`.
- Antes de migrar um banco com dados, o app copia o arquivo para `psigestor.db.pre-v<N>`.

## Segurança e dados

- **Nunca commitar**: `*.db`, `*.db.*` (inclui as cópias `pre-vN`), `*.psgb` (backups), `logs/`, `dados_titular_*.txt` e `.claude/settings.local.json`. Todos estão no `.gitignore`; rode `git status` antes de commitar.
- **Só dados fictícios** em testes, documentação, prints (`docs/img`) e exemplos. Dado real de paciente é dado de saúde (LGPD).
- **Dinheiro em centavos**: `long` no Java (`valorCentavos`) e `INTEGER` no banco. Nunca `double` nem `REAL`.

## Textos e codificação

- Todo arquivo de texto em **UTF-8 sem BOM** (`.java`, `.md`, `.sql`, `.css`, `.properties`). O `pom.xml` já fixa UTF-8; ao ler ou gravar arquivo no código, use `StandardCharsets.UTF_8`.
- Textos que o usuário vê (telas, `RegraNegocioException`, modelos de mensagem) e os `.md` levam **acentuação correta** (ç, ã, é...). Comentários de código podem ficar sem acento, como já estão.
- Ao reescrever um arquivo, preserve acentos e quebra de linha (o `README.md` usa CRLF).

## Regras do projeto

- Ao final de cada sprint, atualizar o estado em `docs/BACKLOG.md` (versão do pom, número de testes, última release e tag, e as seções de andamento, pedidos, correções e limitações).
- O número de testes também aparece no `README.md`: mantenha os dois iguais ao real.
