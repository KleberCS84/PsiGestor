# Gerar o instalador Windows (jpackage)

O instalador `PsiGestor-<versao>.exe` leva o Java 21 e o JavaFX embutidos: quem instala nao precisa
ter Java no computador. Ele e gerado pelo `jpackage` do proprio JDK, a partir do perfil Maven `instalador`.

## 1. Pre-requisitos (so na maquina que gera o instalador)

| Item | Versao | Observacao |
|---|---|---|
| JDK | 21 (ex.: Temurin 21) | O `jpackage` e o runtime embutido vem dele. O Maven precisa rodar com esse JDK (`mvn -v` mostra). |
| Maven | 3.9+ | |
| WiX Toolset | **3.x** (ex.: 3.14) | O `jpackage` do JDK 21 usa `candle.exe` e `light.exe` para montar o `.exe`. WiX 4/5 **nao** servem para o JDK 21. Depois de instalar, a pasta `bin` do WiX precisa estar no `PATH` (abra um terminal novo e confira com `candle -?`). |

## 2. Gerar

```bash
mvn -P instalador clean package
```

O que acontece:
1. compila e roda os testes (para pular: `-DskipTests`, nao recomendado para uma versao que vai para usuarios);
2. o jar do app e as dependencias (JavaFX com as bibliotecas nativas `-win`, sqlite-jdbc) vao para `target/jpackage-input/`;
3. o `jpackage` monta um runtime Java so com os modulos necessarios e gera o instalador.

Resultado: `target/instalador/PsiGestor-<versao>.exe`.

Sem o WiX no `PATH`, o passo 3 falha com `Can not find WiX tools (light.exe, candle.exe)`.

A versao vem do `<version>` do `pom.xml`. O `jpackage` exige versao numerica (`1.2.3`): nao use sufixos como `-SNAPSHOT` em uma versao de distribuicao.

## 3. O que o instalador faz

| Item | Comportamento |
|---|---|
| Pasta do programa | Padrao `C:\Program Files\PsiGestor`; o usuario pode escolher outra (`--win-dir-chooser`). |
| Atalhos | Menu Iniciar (grupo **PsiGestor**) e area de trabalho. |
| Icone | `src/packaging/windows/psigestor.ico`. |
| Licenca | Mostra o `LICENSE` (MIT) durante a instalacao. |
| Atualizacao | O `--win-upgrade-uuid` e fixo no `pom.xml`: instalar uma versao nova substitui a antiga, sem duplicar. **Nunca troque esse UUID.** |
| Dados | **Nao ficam na pasta do programa.** O banco, os logs e as copias de migracao ficam em `%USERPROFILE%\PsiGestor` (ex.: `C:\Users\<voce>\PsiGestor`). |
| Desinstalar | Remove so a pasta do programa e os atalhos. **A pasta de dados `%USERPROFILE%\PsiGestor` nao e apagada**: o instalador nao sabe que ela existe. Para apagar os dados de verdade, apague essa pasta manualmente (depois de conferir que ha backup, se ainda houver obrigacao de guarda do prontuario). |

Depois de instalar, confira a versao em **Sobre** (menu lateral ou link na tela de login) e no titulo da janela.

## 4. Sem WiX: pasta portatil (app-image)

Para testar o empacotamento sem o WiX, gere a entrada e chame o `jpackage` com `--type app-image`:

```bash
mvn -P instalador package -DskipTests "-Dexec.skip"
jpackage --type app-image --name PsiGestor --app-version 0.1.0 \
  --input target/jpackage-input --main-jar psigestor-0.1.0.jar \
  --main-class br.com.kleber.psigestor.ui.Launcher \
  --icon src/packaging/windows/psigestor.ico --dest target/app-image
```

Resultado: `target/app-image/PsiGestor/PsiGestor.exe` (pasta com o runtime embutido, sem instalador, sem atalhos).
Os dados continuam em `%USERPROFILE%\PsiGestor`.

## 5. Checklist antes de distribuir uma versao

- [ ] `mvn clean test` verde.
- [ ] `<version>` do `pom.xml` atualizado (o banco migra sozinho; a copia `psigestor.db.pre-v<N>` e criada antes).
- [ ] Mudou o texto dos termos de uso? Novo `src/main/resources/termos/termos-v<N>.md` e `Termos.VERSAO_ATUAL = N`: todos aceitam de novo no proximo login.
- [ ] Instalar em uma maquina limpa (sem Java), abrir, conferir **Sobre**.
- [ ] Instalar por cima da versao anterior: dados preservados, um unico item em "Aplicativos instalados".
- [ ] Desinstalar: `%USERPROFILE%\PsiGestor` continua la.
