# Politica de dados do paciente - anonimizacao e guarda

## 1. Regra implementada: anonimizacao do cadastro (LGPD art. 18, IV)

`PacienteService.anonimizarPaciente(id)`, botao **"Anonimizar (LGPD)"** na tela Pacientes.

| Item | Regra |
|---|---|
| Quem pode | Somente usuario com papel **psicologo**. Secretaria e recusada. |
| Quando | Somente se a data de referencia tiver **mais de 5 anos** (guarda minima do registro documental, Res. CFP 01/2009). Referencia = a data **mais recente** entre o **ultimo atendimento** (qualquer status, inclusive futuro), o **ultimo registro de prontuario** (`evolucao.registrado_em`, inclusive registros sem atendimento) e o **cadastro** (`paciente.criado_em`). |
| O que muda | `nome` vira `Paciente anonimizado #<id>`; `cpf`, `data_nascimento`, `telefone`, `email`, `contato_emergencia`, `responsavel_legal` e `numero_carteirinha` viram vazios (NULL). O paciente e **arquivado** e `anonimizado_em` recebe a data/hora. |
| Depois de anonimizado | O cadastro **nao pode** ser editado, desarquivado, anonimizado de novo nem receber agendamentos (o sistema recusa). |
| O que e mantido | O `id` do paciente, o `convenio_id`, os atendimentos, os valores e pagamentos (financeiro), os consentimentos e o **prontuario**, que **nao e alterado**: continua cifrado, ligado ao mesmo id e legivel pelo psicologo autor. |
| Auditoria | Evento `ANONIMIZACAO_PACIENTE` em `log_acesso`, gravado na mesma transacao da alteracao. |
| Confirmacao | A tela exige digitar o nome atual do paciente; se nao conferir, nada e alterado. |
| Reversao | **Irreversivel** no sistema. Os dados originais podem continuar em **backups** feitos antes da anonimizacao, que seguem a propria politica de retencao; **restaurar** um desses backups traz os dados de volta (ver 3.2). |

Campo mantido de proposito: `convenio_id` (referencia ao convenio, nao identifica a pessoa e sustenta o financeiro). O texto livre `consentimento.observacao` nao e alterado nesta versao.

## 2. Decisao pendente: eliminacao do prontuario apos o prazo

A eliminacao do conteudo do prontuario (`evolucao`) depois da guarda minima **ainda nao foi decidida** e **nao esta implementada**. Hoje:

- os triggers `trg_evolucao_sem_update` e `trg_evolucao_sem_delete` impedem alterar ou apagar registros;
- a anonimizacao acima nao toca no prontuario.

Antes de implementar, e preciso definir:

1. se o prontuario sera eliminado, mantido indefinidamente ou entregue ao titular apos o prazo (Res. CFP 01/2009 e 06/2019, LGPD arts. 15-16);
2. quem autoriza e como fica registrado (termo, auditoria);
3. como tratar backups que ainda contem o registro;
4. como compatibilizar com os triggers de imutabilidade (ex.: eliminar a DEK em vez de apagar linhas).

## 3. Backup, restauracao e exportacao (REVISAO.md A5 parcial e B4)

### 3.1 Backup cifrado (`.psgb`)

Tela **Privacidade & Seguranca**, botao **"Gerar backup agora"**.

| Item | Regra |
|---|---|
| Quem pode | Somente **psicologo**. |
| Senha | Senha **de backup**, pedida na hora, duas vezes, minimo de 12 caracteres. **Nao e armazenada** em lugar nenhum: perdida a senha, o backup nao pode ser restaurado. Guarde-a separada do arquivo. |
| Como e feito | `VACUUM INTO` para um temporario (`backup-*`) na propria pasta do banco, `PRAGMA integrity_check` no temporario, cifragem e exclusao do temporario. Temporarios `backup-*`/`restauracao-*` deixados por uma interrupcao (queda de energia) sao apagados na proxima vez que o PsiGestor abre. |
| Cifra | AES-256-GCM; chave = PBKDF2-HMAC-SHA256 (600.000 iteracoes, salt aleatorio de 16 bytes por arquivo). |
| Formato | v2: `"PSGB"` + versao (1 byte) + iteracoes (4 bytes) + salt (16) + IV (12) + dados cifrados com a tag GCM. O cabecalho inteiro e autenticado (AAD). Backups v1 (sem o campo de iteracoes, 600.000 fixas) continuam abrindo. |
| Nome | `psigestor_backup_<aaaaMMdd_HHmmss>_<8 hex aleatorios>.psgb`; nunca sobrescreve um arquivo existente. |
| Auditoria | Evento `BACKUP`. |

**Lembrete.** O backup e responsabilidade do usuario (termos de uso, item 2). Para o psicologo logado, o
Painel mostra um aviso com o botao **"Fazer backup agora"** quando o ultimo evento `BACKUP` da trilha tem mais
de 7 dias (`BackupService.INTERVALO_LEMBRETE`) ou quando nunca houve backup. A secretaria nao ve o aviso
(nao pode gerar backup). Restauracao (`RESTAURACAO_BACKUP`) nao conta como backup.

### 3.2 Restauracao

Botao **"Restaurar backup..."** (somente psicologo): escolher o `.psgb`, informar a senha, confirmar.

1. O arquivo e decifrado para um temporario; senha errada ou arquivo corrompido/adulterado e recusado **sem alterar nada**.
2. `PRAGMA integrity_check` e conferencia de que e um banco do PsiGestor com versao de esquema igual ou anterior a do programa (anterior e migrada ao abrir; posterior e recusada).
3. Evento `RESTAURACAO_BACKUP` gravado no banco atual e no banco restaurado.
4. O banco atual e copiado para `psigestor.db.antes-restauracao-<aaaaMMdd_HHmmss>` na pasta do PsiGestor e entao substituido.
5. O PsiGestor e fechado e precisa ser aberto de novo.

A copia `antes-restauracao` contem os dados em claro (como o proprio banco): apague-a quando nao for mais necessaria. A mensagem final da restauracao mostra onde ela ficou e tem o botao **"Abrir pasta"**.

**Atencao:** apos restaurar, tudo volta a ser como era na data do backup (o aviso de confirmacao lista isto):

- as senhas dos usuarios voltam a ser as vigentes na data do backup. Senhas trocadas depois disso deixam de valer; quem nao lembrar a senha antiga precisa que ela seja redefinida;
- usuarios **desativados** depois do backup voltam a ficar **ativos** e conseguem entrar: revise a tela Equipe e desative-os de novo;
- pacientes **anonimizados** depois do backup voltam com os dados pessoais: refaca as anonimizacoes;
- os **codigos de recuperacao** voltam a ser os da data do backup: um codigo gerado depois deixa de funcionar e um codigo ja substituido volta a valer. Gere um novo codigo (Trocar senha) apos restaurar (ver 4).

### 3.2.1 Atualizacao do banco (migracao de versao)

Quando uma versao nova do PsiGestor precisa atualizar a estrutura do banco, antes disso ela grava uma copia integra em `psigestor.db.pre-v<versao>` (em claro, como o banco). Se a atualizacao falhar, nada e alterado, o programa mostra onde esta a copia e fecha. Nao abra o banco atualizado com uma versao mais antiga do programa.

### 3.3 Exportacao de dados do titular (LGPD art. 18, II e V)

| Item | Regra |
|---|---|
| Quem pode | Somente **psicologo**. |
| Destino | Pasta escolhida pelo usuario. |
| Nome | `dados_titular_<aaaaMMdd_HHmmss>_<8 hex aleatorios>.txt` (sem o id do paciente); nunca sobrescreve. |
| Conteudo | TXT **nao cifrado** (e entregue ao titular). A tela avisa para **apagar o arquivo do computador (e da lixeira) depois de entregar**. |
| Auditoria | Evento `EXPORTACAO_DADOS_TITULAR`. |

### 3.4 Decisao: banco principal sem cifra no aplicativo

O arquivo `~/PsiGestor/psigestor.db` **continua sem cifra** no PsiGestor: so o conteudo do prontuario e cifrado (por psicologo). CPF, contatos, agenda e financeiro ficam legiveis a quem copiar o arquivo. Por isso o item A5 da revisao esta marcado como **parcial**.

Recomendacao obrigatoria para o computador do consultorio:

- ativar o **BitLocker** (Windows Pro/Enterprise) ou a **Criptografia de dispositivo** (Windows Home compativel) no disco onde fica a pasta do usuario;
- guardar a chave de recuperacao do BitLocker fora do computador;
- usar conta do Windows com senha e bloqueio de tela automatico;
- guardar os backups `.psgb` em midia separada (pendrive/disco externo, de preferencia tambem cifrado).

## 4. Recuperacao de acesso (codigo de recuperacao)

Senha perdida sem codigo = prontuario ilegivel para sempre (a chave do prontuario so abre com a senha). O codigo de recuperacao e uma segunda forma de abrir a mesma chave.

| Item | Regra |
|---|---|
| Quem tem | Somente **psicologo** (so ele tem chave de prontuario). |
| Codigo | 128 bits aleatorios (`SecureRandom`) em base32 sem caracteres ambiguos (sem I, L, O, U), mais 2 caracteres de verificacao: `XXXX-XXXX-XXXX-XXXX-XXXX-XXXX-XXXX`. Na digitacao, minusculas, espacos, `O` (= 0) e `I`/`L` (= 1) sao aceitos; erro de digitacao e apontado sem gastar tentativa. |
| Exibicao | **Uma unica vez**, com botoes **Copiar** e **Imprimir**. A janela so fecha depois de digitar os 4 ultimos caracteres. Guarde fora do computador (papel em local seguro, cofre de senhas). |
| Armazenamento | O codigo **nao e gravado** em lugar nenhum (nem em log). O banco guarda so um segundo embrulho da chave do prontuario: `usuario.dek_recuperacao` (IV + chave cifrada com AES-256-GCM), `salt_recuperacao` e `iteracoes_recuperacao` (PBKDF2-HMAC-SHA256, 600.000). Nulos = codigo ainda nao gerado. |
| Quando e gerado | No primeiro acesso, logo apos criar o psicologo. Psicologo que ainda nao tem codigo (ex.: banco anterior a v7) recebe um aviso **a cada login** ate gerar. Em **Trocar senha**, "Gerar novo codigo de recuperacao". Gerar pede a senha atual; o codigo anterior deixa de valer. |
| Recuperacao | Tela de login, **"Esqueci a senha"**: login + codigo + nova senha (minimo 12). O codigo abre a chave do prontuario, que e embrulhada pela nova senha; um **novo codigo** e gerado e mostrado (o usado deixa de valer). Tudo em uma transacao. Depois, entra-se com a nova senha; o prontuario continua legivel. |
| Tentativas erradas | Mesmo bloqueio progressivo do login, contado junto com as senhas erradas do mesmo login (5 falhas: 30 s, dobrando ate 15 min). Login inexistente, secretaria ou psicologo sem codigo recebem a mesma resposta de codigo errado. |
| Auditoria | `RECUPERACAO_GERADA`, `RECUPERACAO_USADA`, `RECUPERACAO_FALHOU` em `log_acesso` (sem o codigo). |
| Secretaria | Nao tem chave de prontuario nem codigo. Se esquecer a senha, um psicologo define uma nova na tela **Equipe** ("Redefinir senha (secretaria)"), evento `REDEFINICAO_SENHA`. A senha de psicologo nunca e redefinida por outra pessoa. |
| Riscos | Quem tiver o codigo e o login tem acesso total ao prontuario daquele psicologo: guarde-o como uma chave do consultorio. Perdidos a senha **e** o codigo, o prontuario continua irrecuperavel. Restaurar um backup traz de volta o codigo vigente na data do backup (ver 3.2). |

## 5. Termos de uso (aceite)

| Item | Regra |
|---|---|
| Texto | `src/main/resources/termos/termos-v<N>.md`: software "como esta", backup e responsabilidade do usuario, o desenvolvedor nao tem acesso aos dados, nao substitui a orientacao do CFP/CRP. Visivel a qualquer momento em **Sobre > Ver termos de uso**. |
| Quando | Primeiro acesso: antes de criar a conta do psicologo responsavel (recusar = nada e gravado). Depois, no primeiro login de cada usuario, inclusive secretaria. |
| Obrigatorio | Sem aceite a sessao e encerrada e o login nao segue. |
| Registro | `usuario.termos_versao` e `usuario.termos_aceitos_em` (hora local, `yyyy-MM-ddTHH:mm:ss`), migracao v8; evento `TERMOS_ACEITOS` em `log_acesso` com detalhe `v<N>`. Usuarios de bancos anteriores a v8 ficam com versao 0 e aceitam no proximo login. |
| Versao nova | Novo arquivo `termos-v<N+1>.md` e `Termos.VERSAO_ATUAL = N+1`: todos aceitam de novo no proximo login. |
