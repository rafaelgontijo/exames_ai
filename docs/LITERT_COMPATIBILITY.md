# Verificação de compatibilidade LiteRT-LM

Inspeção em 21/10/2026 dos AARs publicados em Google Maven, sem executar o modelo. Após a inspeção, o usuário aprovou elevar o minSdk para 24.

| Versão | minSdk declarado | SHA-256 do AAR inspecionado |
| --- | --- | --- |
| 0.15.0 | 24 | `b398c4745934a6035d192ffce5fdaf4f72a0009830a97b73c017c21f2a92b5bd` |
| 0.16.1 | 24 | `e407719c1a29f2685fcb6aa3feea0b9f7155fe316c66dae053c1b5b2f54cda73` |
| 0.17.1 | 24 | `a8aeaa6b128e9b0f1c7fe5e620932a7030cfff1ac9c0dc133d8c79711de5cf4d` |
| 0.18.1 | 24 | `05c6bdc8acda834efcca12c0ad5d06d2cd8c83e60770f056d8d222f699f3012b` |

Fonte: `https://dl.google.com/dl/android/maven2/com/google/ai/edge/litertlm/litertlm-android/<versão>/litertlm-android-<versão>.aar`. Cada `AndroidManifest.xml` declara `android:minSdkVersion="24"`. O AAR 0.18.1 tem 20905360 bytes e bibliotecas nativas para `arm64-v8a` e `x86_64`. Uma inspeção adicional com readelf confirmou alinhamento 0x4000 (16 KiB) nos segmentos LOAD do liblitertlm_jni.so arm64-v8a do AAR 0.18.1. Isso não comprova execução do modelo, alinhamento final do APK ou desempenho no S25.

O app e todos os módulos Android agora declaram minSdk 24 por autorização explícita do usuário. O desenho aprovado exige verificar o runtime sem elevar API mínima silenciosamente. As versões inspecionadas compatíveis com o piso publicado do modelo LFM não resolvem esse conflito por downgrade. Não foi aplicado `tools:overrideLibrary` para forçar uso em API não declarada pelo fornecedor.

Decisão aprovada: elevar a compatibilidade mínima para API 24 (Android 7.0). Android 6.0 deixa de ser suportado; o S25 permanece dentro do público-alvo. Não foi necessário sobrescrever a declaração mínima do fornecedor.

Foi provisionado um emulador API 24 x86_64 para os testes instrumentados de persistência e Keystore. A execução do modelo no S25 permanece pendente.

Os resultados executados de corpus, domínio e persistência estão em `docs/VALIDACAO.md`. A inspeção do AAR não implica que o modelo já foi integrado ou executado.
