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
- pacientes **anonimizados** depois do backup voltam com os dados pessoais: refaca as anonimizacoes.

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
