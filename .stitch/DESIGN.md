---
name: Meu Exame — MVP
colors:
  background: '#f4f8fd'
  on-background: '#13233a'
  surface: '#f4f8fd'
  surface-dim: '#dceafb'
  surface-bright: '#ffffff'
  surface-container-lowest: '#ffffff'
  surface-container-low: '#f4f8fd'
  surface-container: '#e8f1fc'
  surface-container-high: '#dceafb'
  surface-container-highest: '#d6e6f8'
  surface-variant: '#e8f1fc'
  on-surface: '#13233a'
  on-surface-variant: '#4a5b72'
  outline: '#6b7f99'
  outline-variant: '#d6e6f8'
  primary: '#1f5fbf'
  on-primary: '#ffffff'
  primary-container: '#dceafb'
  on-primary-container: '#0f3a7a'
  secondary: '#1a4fa0'
  on-secondary: '#ffffff'
  secondary-container: '#e8f1fc'
  on-secondary-container: '#0f3a7a'
  tertiary: '#8a5100'
  on-tertiary: '#ffffff'
  tertiary-container: '#fff2d9'
  on-tertiary-container: '#5c3600'
  error: '#9b1c1c'
  on-error: '#ffffff'
  error-container: '#fde4e4'
  on-error-container: '#6e1414'
typography:
  headline-md:
    fontFamily: Figtree
    fontSize: 26px
    fontWeight: '800'
    lineHeight: 32px
    letterSpacing: '-0.01em'
  headline-sm:
    fontFamily: Figtree
    fontSize: 24px
    fontWeight: '800'
    lineHeight: 29px
    letterSpacing: '-0.01em'
  title-lg:
    fontFamily: Figtree
    fontSize: 20px
    fontWeight: '700'
    lineHeight: 26px
    letterSpacing: '0'
  title-md:
    fontFamily: Figtree
    fontSize: 18px
    fontWeight: '700'
    lineHeight: 24px
    letterSpacing: '0'
  title-sm:
    fontFamily: Figtree
    fontSize: 16px
    fontWeight: '700'
    lineHeight: 22px
    letterSpacing: '0'
  body-lg:
    fontFamily: Figtree
    fontSize: 16px
    fontWeight: '400'
    lineHeight: 22px
    letterSpacing: '0'
  body-md:
    fontFamily: Figtree
    fontSize: 15px
    fontWeight: '400'
    lineHeight: 21px
    letterSpacing: '0'
  body-sm:
    fontFamily: Figtree
    fontSize: 14px
    fontWeight: '400'
    lineHeight: 20px
    letterSpacing: '0'
  label-md:
    fontFamily: Figtree
    fontSize: 13px
    fontWeight: '700'
    lineHeight: 18px
    letterSpacing: '0'
rounded:
  sm: 12px
  DEFAULT: 14px
  md: 16px
  lg: 18px
  xl: 20px
  full: 9999px
spacing:
  unit: 4px
  xs: 4px
  sm: 8px
  md: 12px
  lg: 16px
  xl: 20px
  2xl: 24px
  gutter: 14px
  margin-mobile: 20px
---

# Design System: Meu Exame — MVP
**Project ID:** 2627731492696541504
**Visual Source:** `App de Exames — MVP.html` (4 telas mobile 390×844) + `core-ui` Compose tokens (`docs/PROTOTYPE_EXPERIENCE.md`)

## 1. Visual Theme & Atmosphere

**Meu Exame** adota uma estética clínica acolhedora, calma e de altíssima legibilidade, pensada para pacientes e cuidadores lerem laudos laboratoriais no celular com segurança e zero ruído visual. A paleta parte de um azul clínico confiável sobre um fundo azul-gelo suave (`#F4F8FD`) e superfícies brancas puras (`#FFFFFF`) delimitadas por bordas finas e nítidas em vez de sombras pesadas.

A densidade é deliberadamente baixa e espaçosa: tipografia robusta (Figtree 400–800), alvos de toque generosos (52–56px em CTAs, mínimo 48dp em controles interativos), hierarquia clara entre ação primária em azul sólido e cartões brancos escaneáveis. O idioma visual prioriza acessibilidade (contraste ≥ 4,5:1 em texto e ≥ 3:1 em controles) e transparência documental, sem alarme clínico ou dramatização.

## 2. Color Palette & Roles

### Primary Foundation
- **Ice Mist Canvas** (`#F4F8FD`): Fundo raiz de todas as telas no modo claro.
- **Pure Clinical White** (`#FFFFFF`): Cartões de conteúdo, barra de navegação inferior, botões primários invertidos sobre fundo azul e barra de ação fixa inferior.
- **Soft Sky Tint** (`#E8F1FC`): Fundos de ícones em destaque, blocos de dicas/orientação documental e chips informativos/dentro da referência.
- **Soft Sky Border** (`#D6E6F8`): Bordas decorativas de 1px em cartões e divisores de barras fixas.

### Accent & Interactive
- **Clinical Trust Blue** (`#1F5FBF`): Ação primária, cartões hero de entrada/destaque, ícones ativos na barra inferior, selo da marca e acento de controles selecionados.
- **Deep Navy Blue** (`#0F3A7A`): Títulos principais, marca no cabeçalho, rótulos de botões sobre branco e estado hover/ênfase.
- **Medium Sky Outline** (`#BBD6F6`) / **Accessible Control Outline** (`#6B7F99`): Contorno de 2px em cartões e botões secundários (no Compose, controles interativos usam `#6B7F99` para garantir contraste ≥ 3:1).
- **Pale Ice Text** (`#DCEAFB`): Subtítulos e texto de apoio sobre superfícies `#1F5FBF`.

### Typography & Text Hierarchy
- **Midnight Clinical Ink** (`#13233A`): Texto principal, nomes de exames e valores numéricos extraídos.
- **Slate Blue-Gray** (`#4A5B72`): Subtítulos, datas, faixas de referência, notas explicativas e avisos de rodapé.
- **Cobalt Chip Blue** (`#1A4FA0`): Texto de chips neutros/informativos sobre `#E8F1FC`.

### Functional States
- **Warm Amber Review** (`#FFF2D9` fundo, `#F2D29A` borda, `#8A5100` ícone/chip, `#5C3600` texto): Estados que pedem conferência/revisão documental (`NEEDS_REVIEW` ou fora da faixa informada). Sempre acompanhado de texto e ícone; nunca significa diagnóstico ou urgência.
- **Soft Coral Alert** (`#FDE4E4` fundo, `#F3B8B8` borda, `#9B1C1C` ícone, `#6E1414` texto): Avisos críticos de processamento/limitação ou alertas informativos destacados com texto claro; nunca substitui avaliação médica nem classifica urgência clínica.

> **Tema escuro (Compose):** Equivalentes explícitos em `ExamesTokens.darkScheme` (`#0E1726` fundo, `#16233A` superfície, `#8DB8FF` primária, `#C9DCFF` títulos, `#4A3A00`/`#FFE08A` revisão).

## 3. Typography Rules

- **Família principal:** **Figtree** (humanista-geométrica, excelente altura-x e leitura clara em telas pequenas; pesos 400, 500, 600, 700 e 800; empacotada offline em `core-ui`). Em ferramentas que não suportam Figtree nativamente, usar **Plus Jakarta Sans** ou **Inter** como equivalente mais próximo.
- **Hierarquia:**
  - **Saudação / Hero Heading:** `26px` ExtraBold (`800`), line-height `1.2`, `#0F3A7A`.
  - **Título de fluxo:** `24px` ExtraBold (`800`), line-height `1.2`, `#0F3A7A`.
  - **Marca / Título de cartão hero:** `20px` Bold/ExtraBold (`700–800`).
  - **Top Bar / Títulos de seção / CTA primário:** `18px` Bold (`700`).
  - **Nome de exame / Ações secundárias:** `16px` SemiBold/Bold (`600–700`).
  - **Corpo e valores:** `15–16px` Regular/Bold (`400`/`700`), line-height `1.35–1.4`.
  - **Notas, metadados e consentimento:** `14px` Regular (`400`), `#4A5B72`.
  - **Chips de status e rótulos de navegação:** `13px` Bold (`700`) / Medium (`500`).
- **Escala acessível:** Todos os tamanhos devem escalar até 200% (`sp` no Android) sem sobreposição ou truncamento crítico.

## 4. Component Stylings

### Buttons
- **Primário (sobre fundo claro):** Altura `56px`, raio `14px`, fundo `#1F5FBF`, texto `#FFFFFF` (`18px`, `700`), largura total.
- **Primário invertido (sobre cartão `#1F5FBF`):** Altura `52–56px`, raio `14px`, fundo `#FFFFFF`, texto `#0F3A7A` (`17–18px`, `700`).
- **Secundário contornado:** Altura `56px`, raio `14px`, fundo `#FFFFFF`, borda `2px solid #BBD6F6` (ou `#6B7F99` para ≥ 3:1), texto `#0F3A7A` (`17px`, `700`), ícone `#1F5FBF`.
- **Ação compacta em banner:** Altura `48px`, raio `12px`, fundo `#FFFFFF`, texto `#0F3A7A` (`16px`, `700`).
- **Botão circular de ícone (Ajuda / Voltar):** Mínimo `44×44px` (`48×48dp` no Android), borda sutil ou sem fundo.

### Cards & Containers
- **Cartão Hero / Ação principal:** Fundo `#1F5FBF`, raio `20px`, padding `20–22px`, bloco interno de ícone `48–60px` com raio `16–18px` e fundo `#E8F1FC`.
- **Cartão de conteúdo / Resultado / Item de lista:** Fundo `#FFFFFF`, borda `1px solid #D6E6F8`, raio `16px`, padding `14–16px`, sem sombra (superfície plana sobre `#F4F8FD`).
- **Cartão de resumo / revisão:** Fundo `#FFF2D9`, borda `1px solid #F2D29A`, raio `20px`, padding `18px`.
- **Cartão informativo / Dicas:** Fundo `#E8F1FC`, sem borda, raio `16px`, padding `16px`.

### Status Chips & Badges
- **Formato:** Pílula (`border-radius: 9999px`), padding `6px 10–12px`, `13–14px`, Bold (`700`), `white-space: nowrap`.
- **Revisão / Atenção:** Fundo `#FFF2D9`, texto `#8A5100` (ou `#5C3600`).
- **Informativo / Dentro da referência:** Fundo `#E8F1FC`, texto `#1A4FA0`.
- **Passo numerado:** Círculo `32×32px`, fundo `#E8F1FC`, texto `#1F5FBF` ExtraBold (`800`).

### Navigation
- **Top Bar interna:** Padding `24px 12px 8px`, botão voltar à esquerda (`44×44px`, seta `#0F3A7A`) + título `18px` Bold `#0F3A7A`.
- **Bottom Navigation Bar:** Altura `72px`, fundo `#FFFFFF`, borda superior `1px solid #D6E6F8`, 3 colunas iguais com ícone `22px` + rótulo `13px` (ativo `#1F5FBF` Bold; inativo `#4A5B72` Medium).
- **Sticky Bottom CTA:** Fundo `#FFFFFF`, borda superior `1px solid #D6E6F8`, padding `12px 20px 24px`.

## 5. Layout Principles

- **Viewport base:** Mobile-first `390×844px`, coluna única vertical (`flex-direction: column`), rolagem simples sem colunas aninhadas complexas.
- **Margens e ritmo:** Margem lateral fixa de `20px`; espaçamento vertical entre blocos principais de `14–20px` e `10px` entre itens de lista (grid base de `4px`).
- **Grade de apoio:** Grade de 3 colunas iguais (`gap: 10px`) apenas para cartões compactos de passo a passo no Início.
- **Limites éticos e de conteúdo (`AGENTS.md`):** Telas criadas a partir deste Design System preservam a linguagem visual e a clareza do protótipo, mas **nunca** exibem diagnósticos, recomendações clínicas, prazos/urgência ou indicação de especialidades/UBS; priorizam proveniência no laudo ("Ver no laudo"), texto bruto preservado, estado `NEEDS_REVIEW` explícito e privacidade offline no aparelho.

## 6. Design System Notes for Stitch Generation

### Language to Use
- Interface mobile clínica calma, acolhedora e altamente legível, em português do Brasil (pt-BR), com fundo azul-gelo muito claro, cartões brancos planos com bordas finas suaves, cantos generosamente arredondados, ícones lineares limpos (traço 2px) e botões grandes fáceis de tocar.

### Color References
- Fundo **Ice Mist Canvas** (`#F4F8FD`), cartões **Pure Clinical White** (`#FFFFFF`) com borda **Soft Sky Border** (`#D6E6F8`), destaque **Clinical Trust Blue** (`#1F5FBF`), títulos **Deep Navy Blue** (`#0F3A7A`), chips informativos **Soft Sky Tint** (`#E8F1FC`) e revisão **Warm Amber Review** (`#FFF2D9` / `#8A5100`).

### Component Prompts
- **Cartão de entrada:** Cartão arredondado grande em destaque azul com ícone em quadrado suave, título em branco, subtítulo curto e botão branco de largura total com texto escuro.
- **Cartão de medição:** Cartão branco plano com borda sutil contendo nome do exame e chip pílula na primeira linha, valor extraído + faixa de referência do documento na segunda, e ação "Ver no laudo" ou nota documental abaixo.
- **Barra de navegação inferior:** Barra branca limpa com borda superior fina e 3 itens (Início, Exames, Ajuda) com ícone linear e rótulo legível.
