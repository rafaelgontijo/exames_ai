# Meu Exame

App Android em Kotlin e Compose para extrair resultados de exames em PDF digital com IA no aparelho. A tela inicial tem uma ação, **Selecionar PDF**. O app acompanha o processamento por página e mostra os resultados em ordem documental. **Ver no laudo** abre a página com os trechos vinculados destacados. A fonte e os resultados da extração atual ficam em armazenamento cifrado. **Descartar extração** remove ambos.

## Build e testes

Use JDK 21 e Android SDK Platform 36. O app instala a partir do Android 7.0 (API 24). O S25 é o aparelho de referência para a inferência GPU. Prepare o AAR fixado do PDFium e os pesos Gemma 4 E2B antes do build. [Configuração do PDFium](docs/DOCUMENT_EXTRACTION.md) · [Modelo e distribuição](docs/BUNDLED_INFERENCE.md)

```sh
./gradlew assembleDebug test
./gradlew :app:connectedDebugAndroidTest
```

O APK padrão fica em `app/build/outputs/apk/debug/app-debug.apk`. Seus componentes principais são :domain para o contrato e a validação de evidência, :data-private-pdf para importação e armazenamento, :pdf-local para PDFium, :inference-local para o modelo e :app para a interface. O APK inclui os pesos fixados para a primeira execução offline. [Seleção do modelo](validation/models/selection.json)

A variante técnica anterior é acessível em builds locais com `-PlegacyWorkbench=true`. Seu código, testes e documentação continuam disponíveis para a manutenção dos dados legados. [Bancada anterior](docs/WORKBENCH_FLOW.md)

## Escopo e aceite

A primeira entrega usa o PDF digital autorizado pelo titular como fonte do aceite técnico. Em instalação limpa e offline no S25, a extração coincidiu com a lista privada nas 36 medições das seis páginas e preservou uma referência ambígua para conferência. Os testes automatizados exercitam contratos, associação, falhas, cancelamento e interface. O app exibe campos verificados, avisos de cobertura e trechos de referência para conferência. [Fluxo de extração e limites](docs/AI_EXAM_EXTRACTION.md) · [Relatório agregado do aceite](validation/results/simplify-exam-v4-saf-acceptance-s25-2026-11-22.json)
