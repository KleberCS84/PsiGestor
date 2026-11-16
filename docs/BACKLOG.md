# Backlog - PsiGestor

Atualize este arquivo ao final de cada sprint (regra no `CLAUDE.md`).
Fontes: `git log`, `pom.xml`, `docs/REVISAO.md`, `docs/REVISAO_FINAL.md` e `gh release list`.

## Estado atual

Atualizado em: 2026-11-16 (HEAD `dcb0c4d`, branch `main`, sem código pendente).

| Item | Valor |
|---|---|
| Versão do `pom.xml` | 0.2.0 |
| Testes automatizados | 294 (`@Test` em 24 classes; confere com `target/surefire-reports`: 24 suítes, 0 falhas, 0 erros, 0 ignorados). O README também diz 294 |
| Última release | PsiGestor 0.2.0 (marcada "Latest" no GitHub, publicada em 2026-11-13 UTC) |
| Última tag | `v0.2.0` (commit `dcb0c4d`, 2026-11-12) |
| Release anterior | `v0.1.3-piloto` (pré-release, 2026-10-30) |
| Versão do esquema do banco | v13 (`Database.VERSAO_ESQUEMA`; v12 trouxe o agendamento em lote e v13, os lembretes de sessão) |

Histórico resumido das versões:

- 0.1.0 a 0.1.3 (piloto, 19/10 a 30/10): instalador jpackage, tela Sobre, lembrete de backup, termos de uso, inatividade configurável com aviso, edição de agendamento, consultar agenda e enviar horários (WhatsApp, e-mail, .ics).
- 0.2.0 (12/11): sprints 2a a 2d, listadas abaixo.

Tags: além das de versão, existem marcos de sprint (`antes-sprint-*` e `sprint-2c-testes`). A tag `v1.0.0-piloto`, que apontava para o mesmo commit de `v0.1.0-piloto` (`f7ee986`), não existe mais, nem local nem no remoto (conferido em 2026-11-16).

## Em andamento

Nada em andamento: o último commit é a versão 0.2.0. Os ajustes de documentação de 2026-11-16 (`CLAUDE.md`, este backlog e o número de testes do README) ainda não foram commitados.

## Pedidos da psicóloga (piloto)

Entregues na 0.2.0 (o git log não diz quem pediu cada item; confirme se todos vieram da psicóloga):

- [x] Sprint 2a: trocar o paciente de um agendamento; excluir e arquivar paciente.
- [x] Sprint 2b: agendamento em lote (semanal/quinzenal), série "N de M", cancelar esta e as próximas.
- [x] Sprint 2c: lembretes de sessão (aviso à psicóloga e painel "Lembretes pendentes").
- [x] Sprint 2d: alerta de fim de semana e feriado ao agendar.

Entregues na 0.1.x:

- [x] Edição de agendamento e inatividade configurável (0.1.2).
- [x] Consultar agenda e enviar horários ao paciente, com vários status, `{periodo}` na mensagem e .ics só com sessões futuras (0.1.3).

Pendentes: nenhum pedido registrado. Anote aqui os novos pedidos do piloto, com data e quem pediu.

## Correções

Corrigidas e verificadas (detalhes em `docs/REVISAO.md` e `docs/REVISAO_FINAL.md`):

- Revisão 1: 18 de 24 achados corrigidos; parciais A5, B6, B9; abertos M4, M6, B3 (ver "Limitações conhecidas").
- Revisão final: M-1 a M-7 e B-1 a B-6, B-10, B-12 e B-13 corrigidos. B-7, B-8, B-9 e B-11 seguem abertos.

Correções pendentes: nenhuma que bloqueie o piloto. Os itens abertos são riscos aceitos e estão na seção abaixo.

## Ideias futuras

- [ ] Declaração de comparecimento (documento para o paciente).
- [ ] Lembretes de amanhã (aviso das sessões do dia seguinte, em complemento ao painel de lembretes pendentes).
- [ ] PDF da semana (agenda semanal exportável).

Já listados no contexto do projeto e ainda não feitos: relatórios em PDF (agenda do dia, extrato de repasse) e documentos psicológicos conforme a Res. CFP 06/2019. Confira em `docs/PsiGestor_contexto_projeto.md` antes de priorizar.

## Limitações conhecidas

Riscos aceitos de propósito. Cada um tem origem em `docs/REVISAO.md` (seção 4) ou `docs/REVISAO_FINAL.md`.

| Id | Limitação | Risco |
|---|---|---|
| M4 | Prontuário cifrado sem AAD (dados autenticados associados) | Quem escreve no `.db` e remove as triggers pode mover um registro cifrado entre pacientes ou alterar metadados sem a decifragem acusar |
| M6 | Troca de senha não rotaciona a DEK | Cópia antiga do `.db` ou backup anterior à troca continua abrindo todo o prontuário com a senha vazada |
| B3 | Arquivos criados com permissões padrão | Banco, backups, logs e exportações herdam a ACL da pasta; outra conta do Windows com acesso pode copiá-los |
| B6 (restante) | Senha em memória | A `String` digitada na UI e a senha de `cadastrar` não são zeradas; a DEK fica na memória até o GC. Um dump do processo pode expô-las |
| B-7 | Status e recibo frágeis | `AGENDADO -> REALIZADO` vale para sessão futura; `marcarReciboEmitido` aceita atendimento cancelado, não pago ou de convênio |
| B-8 | Exportação do titular incompleta | O relatório não traz convênio/carteirinha nem valores e pagamentos, e pode mostrar caminho de arquivo em erro (LGPD art. 18, II) |
| B-9 | PBKDF2 na thread da tela | Login, backup e restauração congelam a janela por 1 a 3 s; mover para thread de fundo exige rever a conexão JDBC única |
| B-11 | Limitador de login sem decaimento | O contador nunca zera com o tempo e guarda em memória cada texto digitado no campo de login |

Também abertos, mas fora da lista pedida: cópia `antes-restauracao` em claro (M-3 parcial), contador de login em memória (M1), restauração de backup antigo que reativa acessos e desfaz anonimizações (M-7, só aviso) e percentual de repasse anterior à v6 (M-5). O B-12 aparece na seção 4 do `REVISAO.md` como aberto, mas o `REVISAO_FINAL.md` o marca como corrigido, então não entra aqui.
