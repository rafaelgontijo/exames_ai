# Apresentação literal de spans ao modelo

O prompt `biochemistry-evidence/5` apresenta trechos da própria fonte com texto
literal `raw`, os aliases de todos os spans originais e posições normalizadas
`x/y` quando conhecidas. Não recebe respostas do gabarito, valores do parser,
classificação, histórico ou correções humanas. O schema de saída permanece
`biochemistry-fields/1`; a validação de evidência não mudou.

O agrupamento é somente visual: palavras consecutivas na entrada, na mesma
página/dimensões/rotação, com sobreposição vertical de pelo menos metade da menor
altura, avanço horizontal e lacuna de até 0,75 dessa altura. Limita a agregação a
128 spans e 512 caracteres; um fragmento isolado maior é preservado sob a quota
UTF-8 total, sem corte silencioso. Não reordena spans nem cruza texto de controle.
Geometria ausente, outra linha, lacuna maior, ordem invertida ou limite excedido
produzem grupos separados; nenhum trecho é descartado. O agrupamento não atribui
rótulos como resultado ou referência.

A junção segue somente `spaceBefore=true` entre textos sem whitespace explícito;
`false` ou desconhecido não fabricam espaços. O validador confronta a proposta com
os mesmos fragmentos originais e ainda exige IDs existentes, contiguidade,
propriedade da linha/campo e contexto. As coordenadas arredondadas servem apenas à
apresentação; as regiões exatas permanecem na evidência. Compor um grupo não
transforma sua região aproximada em nova evidência.

A serialização é incremental, sob o teto combinado de 16 KiB UTF-8 de instrução
e documento. Fragmentos enormes, excesso de aliases, escapes e Unicode continuam
sujeitos aos limites, sem truncar a fonte silenciosamente. A requisição só existe
em memória e não é persistida. Testes JVM cobrem limites, separação conservadora,
texto de controle, espaçamento, IDs e preservação de evidência.

Motivo: no ensaio S25, v3/v4 extraíram os cinco analitos/resultados/unidades de um
PDF digital, mas omitiram todas as referências. Outros casos tiveram erro de
schema ou associação; todos foram rejeitados sem completar JSON ou afrouxar o
fallback. Oito pares v3 tiveram zero páginas aceitas, reprovando o aceite A/B.
O controle nativo de duas linhas v3 passou, mas não substitui esse resultado.

O build e 269 testes JVM passaram. Após reconectar o S25, o diagnóstico em
DEVELOPMENT aceitou as cinco observações do PDF digital simple-v1, incluindo
referências e spans. O layout footnote-v1 continuou rejeitado por omissão de
referências; seus limites não foram relaxados. Evidência:
`validation/results/full-page-protocol5-development-s25-2026-11-03.json`.
Os controles nativos completos v5 e a nova rodada A/B passaram no S25. O sinal
de aceite está ativo somente no aparelho de referência. Oito casos DEVELOPMENT
produziram uma página aceita e sete fallbacks; os erros permanecem visíveis. Ver
`S25_FINAL_VALIDATION.md` e `validation/models/selection.json`.
