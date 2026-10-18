# Pontos de atenção legais — PsiGestor

> **Aviso:** este documento é um levantamento técnico feito por um desenvolvedor, não um parecer jurídico.
> Antes de usar com pacientes reais ou vender o sistema, valide com um advogado (LGPD/saúde) e com o CRP da sua região.
> Normas mudam: confira sempre a versão vigente.

---

## 1. O que foi aproveitado do sistema de referência e o que ficou de fora

O TproSystem (vivance.net.br) serviu só como **referência de funcionalidades** (painel do dia, agenda, pacientes,
profissionais, serviços, convênios & TUSS, regras de repasse, aniversariantes, desempenho financeiro).

| Item do sistema de referência | Decisão no PsiGestor | Por quê |
|---|---|---|
| Nome, marca, logotipo, cores e layout idêntico | **Não copiado.** Nome, tema e telas próprios | Marca (Lei 9.279/96) e identidade visual/código (Leis 9.609/98 e 9.610/98) são protegidos. Funcionalidade genérica (ter uma agenda) não é |
| Código-fonte / dados | **Nada foi copiado nem extraído.** Os dados de pacientes que aparecem no sistema de referência não foram lidos nem reaproveitados | Dados de saúde de terceiros: tratamento sem base legal (LGPD art. 11) |
| Painel "Resumo de hoje" | Mantido (atendimentos, aguardando, aniversariantes) | Sem risco |
| Aniversariantes | Mantido, **sem envio automático de mensagem** | Mensagem a paciente pode revelar o vínculo terapêutico a terceiros (sigilo). Só contatar quem consentiu (tela Consentimentos) |
| Convênios & TUSS | Mantido, **sem embutir tabelas** | Ver item 6 |
| Regras de repasse | Mantido (% retido pela clínica por profissional) | Exige contrato escrito entre clínica e psicólogo |
| "Desempenho dos profissionais" (ranking) | Virou **relatório de repasse** | Mesmo dado, foco em prestação de contas e não em competição |
| Prontuário | **Adicionado** (o sistema de referência é focado em agenda/financeiro) | É o coração do trabalho do psicólogo e o ponto de maior risco legal — por isso recebeu os controles do item 3 |

---

## 2. LGPD (Lei 13.709/2018)

| Exigência | Onde está no código |
|---|---|
| Dado de saúde é **dado pessoal sensível** (art. 5º, II; art. 11) | Prontuário cifrado (AES-256-GCM), acesso só do psicólogo autor |
| Base legal: tutela da saúde por profissional de saúde (art. 11, II, "f") | Não exige consentimento para o atendimento em si; o sistema registra o **contrato terapêutico** como ciência |
| Consentimento específico para usos extras (lembretes por WhatsApp/e-mail, atendimento online) | `TipoConsentimento` + histórico que nunca é apagado (revogação = novo registro) |
| Minimização (art. 6º, III) | CPF opcional; dica na tela de cadastro |
| Direitos do titular — acesso e portabilidade (art. 18) | Botão **Exportar dados (LGPD)** → `PacienteService.exportarDadosDoTitular` |
| Pedido de exclusão (art. 18, VI) × guarda obrigatória | Não há exclusão: a guarda decorre de obrigação regulatória (art. 16, I). Usa-se **arquivar** |
| Segurança (art. 46) | Senha com PBKDF2 (210 mil iterações), troca de senha sem recifrar nem expor o prontuário (criptografia de envelope), prontuário cifrado, perfis de acesso, log de acesso imutável (triggers) |
| Incidente de segurança (art. 48) | Checklist na tela Privacidade. Regulamento de comunicação de incidentes da ANPD (Res. CD/ANPD nº 15/2024) |
| Encarregado (DPO) | Verificar a Res. CD/ANPD nº 2/2022 (agentes de tratamento de pequeno porte): tratar dado sensível pode afastar a dispensa |

**O que o código NÃO resolve sozinho (é responsabilidade de quem usa):**
- Criptografia do disco do computador (BitLocker no Windows) — o cadastro (nome, telefone) fica legível no arquivo `.db`.
- Política de privacidade e termo/contrato terapêutico em papel ou digital.
- Guardar os backups em local seguro e testar a restauração.

---

## 3. Conselho Federal de Psicologia (CFP)

| Norma | Exigência | Implementação |
|---|---|---|
| Código de Ética Profissional (Res. CFP 10/2005), art. 9º | **Sigilo** | Secretária não acessa o prontuário; outro psicólogo da clínica também não lê registros que não são seus (chave pessoal) |
| Código de Ética, art. 8º | Atendimento de **menor** depende de autorização de responsável | Cadastro de menor de 18 exige responsável legal |
| Res. CFP 01/2009 (registro documental) | Registro **obrigatório**, guarda por **no mínimo 5 anos** | Não existe excluir; triggers no banco bloqueiam UPDATE/DELETE do prontuário; correção = **retificação** |
| Res. CFP 06/2019 (documentos escritos) | Laudos, relatórios, declarações seguem modelo próprio | **Fora do MVP** — gerar documento psicológico exige seguir a estrutura da resolução |
| Res. CFP 11/2018 (serviços por TIC) | Atendimento online exige **cadastro no e-Psi** | Modalidade ONLINE só agenda com consentimento registrado; o sistema **não faz videochamada** |
| SATEPSI / Res. CFP 31/2022 (avaliação psicológica) | Testes psicológicos são de **uso privativo** e têm direitos autorais das editoras | **Não incluído**: nada de aplicação/correção de testes. ⚠️ Atenção ao reaproveitar o projeto de preenchimento de instrumentos: não distribua itens, gabaritos ou normas de testes comerciais |

---

## 4. Fiscal

- **Recibo de pessoa física:** desde 2025 os profissionais de saúde PF devem emitir recibo pelo **Receita Saúde** (app/portal da Receita Federal).
  O PsiGestor **não emite documento fiscal**; ele só controla quais atendimentos ainda estão com recibo pendente (card "Recibos a emitir").
- **Pessoa jurídica:** emissão de NFS-e é feita no sistema da prefeitura / padrão nacional — fora do escopo.
- Valores do módulo Financeiro são **gerenciais**; o contador é quem fecha a parte tributária.

---

## 5. Convênios (ANS)

- **TUSS** (terminologia) é publicada pela ANS. O sistema só guarda o código que o usuário digita.
- Não embutimos tabelas de preço de terceiros (ex.: CBHPM tem direitos autorais da AMB).
- **Faturamento TISS** (envio de guias em XML às operadoras) ficou **fora do MVP**: exige seguir a versão vigente do padrão TISS e homologar com cada operadora.

---

## 6. Se um dia virar produto vendido

| Situação | Ponto de atenção |
|---|---|
| Vender como **app desktop** (dados ficam no computador do cliente) | Você não trata os dados → risco menor. Precisa de **licença de uso** e isenção de garantia |
| Vender como **SaaS/nuvem** | Você passa a ser **operador** (LGPD art. 39): contrato de tratamento de dados, servidor seguro, plano de incidentes, eventual transferência internacional (art. 33) |
| Usar **IA** sobre o prontuário | Enviar texto clínico para API externa = compartilhar dado sensível com terceiro, possivelmente fora do país. Só com anonimização, base legal clara e contrato. Mantido fora do MVP de propósito |
| Bibliotecas usadas | JavaFX (GPL + Classpath Exception), SQLite (domínio público), sqlite-jdbc (Apache 2.0): permitem uso comercial |

---

## Fontes consultadas
- Resolução CFP nº 1/2009 — https://www.normasbrasil.com.br/norma/resolucao-1-2009_111535.html
- Resolução CFP nº 1/2009 (legisweb) — https://www.legisweb.com.br/legislacao/?id=112145
- Recibo eletrônico de saúde obrigatório a partir de 2025 — https://www.coad.com.br/home/noticias-detalhe/129492/recibo-eletronico-de-servicos-de-saude-sera-obrigatorio-a-partir-de-2025
- Receita Saúde para psicólogo — https://agilize.com.br/artigos/recibo-digital-receita-saude-psicologo
- LGPD — https://www.planalto.gov.br/ccivil_03/_ato2015-2018/2018/lei/l13709.htm
