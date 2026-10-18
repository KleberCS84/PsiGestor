# Politica de dados do paciente - anonimizacao e guarda

## 1. Regra implementada: anonimizacao do cadastro (LGPD art. 18, IV)

`PacienteService.anonimizarPaciente(id)`, botao **"Anonimizar (LGPD)"** na tela Pacientes.

| Item | Regra |
|---|---|
| Quem pode | Somente usuario com papel **psicologo**. Secretaria e recusada. |
| Quando | Somente se a data de referencia tiver **mais de 5 anos** (guarda minima do registro documental, Res. CFP 01/2009). Referencia = inicio do **ultimo atendimento** do paciente (qualquer status, inclusive futuro); se nunca houve atendimento, a data de **cadastro** (`paciente.criado_em`). |
| O que muda | `nome` vira `Paciente anonimizado #<id>`; `cpf`, `data_nascimento`, `telefone`, `email` e `contato_emergencia` viram vazios (NULL). |
| O que e mantido | O `id` do paciente, os atendimentos, os valores e pagamentos (financeiro), os consentimentos e o **prontuario**, que **nao e alterado**: continua cifrado, ligado ao mesmo id e legivel pelo psicologo autor. |
| Auditoria | Evento `ANONIMIZACAO_PACIENTE` em `log_acesso`, gravado na mesma transacao da alteracao. |
| Confirmacao | A tela exige digitar o nome atual do paciente; se nao conferir, nada e alterado. |
| Reversao | **Irreversivel** no sistema. Os dados originais podem continuar em **backups** feitos antes da anonimizacao, que seguem a propria politica de retencao. |

Campos nao incluidos nesta versao: `responsavel_legal`, `numero_carteirinha` e `convenio_id` continuam como estao.

## 2. Decisao pendente: eliminacao do prontuario apos o prazo

A eliminacao do conteudo do prontuario (`evolucao`) depois da guarda minima **ainda nao foi decidida** e **nao esta implementada**. Hoje:

- os triggers `trg_evolucao_sem_update` e `trg_evolucao_sem_delete` impedem alterar ou apagar registros;
- a anonimizacao acima nao toca no prontuario.

Antes de implementar, e preciso definir:

1. se o prontuario sera eliminado, mantido indefinidamente ou entregue ao titular apos o prazo (Res. CFP 01/2009 e 06/2019, LGPD arts. 15-16);
2. quem autoriza e como fica registrado (termo, auditoria);
3. como tratar backups que ainda contem o registro;
4. como compatibilizar com os triggers de imutabilidade (ex.: eliminar a DEK em vez de apagar linhas).
