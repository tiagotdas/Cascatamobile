📊 Waterfall Studio Pro
​Especialista em Gráficos de Cascata (Bridge / Waterfall) e Análise de Variação Acumulada.
​O Waterfall Studio Pro é uma aplicação web Single-File (HTML5 + Vanilla JS + Tailwind CSS + Chart.js) projetada para profissionais de FP&A, Finanças Corporativas, Consultoria Estratégica e Business Intelligence. A ferramenta transforma dados tabulares ou entradas manuais em gráficos de cascata executivos (estilo McKinsey / BCG) com reconciliação matemática exata, zero dependências de servidor e total privacidade (100% client-side).
​✨ Principais Funcionalidades
​1. Modelagem e Precisão Matemática
​Dois Modos de Operação:
​Ponte entre Períodos (Bridge): Explica a variação entre uma Base Inicial (ex: Receita Q1) e uma Base Final (ex: Receita Q2) através de eventos intermediários (+/-) e subtotais (=).
​Fluxo Acumulado Livre: Parte do zero e acumula entradas e saídas até formar o coluna de Saldo Final.
​Motor de Ponto Fixo e Validação (BigInt): Todos os cálculos utilizam inteiros escalados (\times 10.000) validados em tempo real contra um oráculo independente em BigInt, eliminando erros clássicos de ponto flutuante (0.1 + 0.2 !== 0.3).
​Reconciliação de Divergências (Plug Hachurado): Detecta automaticamente se a soma dos eventos não fecha com a Base Final (Q_1 + \sum \text{Eventos} \neq Q_2). Oferece controle de tolerância estrita (0), absoluta ou relativa sobre \vert{}\Delta\vert{}, além da opção de inserir automaticamente uma barra hachurada "Outros / Não Explicado".
​2. Data Storytelling Executivo
​Arco Superior de Variação Total (\Delta Bracket): Desenha automaticamente uma chave superior conectando o pilar inicial ao final, destacando a variação líquida absoluta e percentual.
​Subtotais Intermediários (=): Permite inserir pilares de subtotal em qualquer ponto da ponte (ex: Receita Bruta Potencial antes de deduções).
​Quebra de Eixo Y (//): Zoom automático nas variações quando as bases iniciais/finais são muito maiores que os eventos individuais, desenhando o símbolo de quebra // na base do eixo vertical.
​Rótulos Inteligentes: Exibição opcional do percentual que cada evento representa sobre a Base Inicial diretamente nas barras.
​Paletas Inclusivas e Modo Escuro: Três temas executivos (Corporativo Padrão, Daltonismo Seguro e Monocromático Executivo) e suporte completo a Dark Mode.
​3. Importação Inteligente (Excel / Google Sheets / SAP)
​Validação Estrita por Locale (pt-BR, en-US, Auto): Reconhece agrupamentos de milhar e decimais sem falhas silenciosas, além de suportar sinais negativos à direita padrão SAP (ex: 1.234,56- ou 170-) e formato contábil entre parênteses (170).
​Prévia Auditável Antes de Aplicar: Exibe a tabela interpretada linha a linha, destaca ambiguidades ou erros de digitação e mostra a equação de controle (Q_1 + \sum \text{Eventos vs } Q_2) antes de substituir os dados do gráfico.
​4. Exportação Profissional Sem Perdas
​PNG 16:9 Full HD (1920x1080): Exporta ou copia diretamente para a área de transferência (Ctrl+V no PowerPoint/Slides) sempre padronizado em alta definição e com fundo claro executivo, independentemente do tamanho da janela ou tema ativo.
​SVG Vetorial Escalável: Gera arquivo .svg nativo com suporte a padrões hachurados para edição direta no Figma, Illustrator ou PowerPoint.
​Planilha .XLSX Nativa (Online e Offline): Conta com integração SheetJS e um gerador OpenXML + ZIP binário nativo embutido em JavaScript puro, garantindo o download de planilhas .xlsx com células numéricas reais mesmo sem conexão com a internet ou em redes corporativas restritas.
​TSV Numérico Puro vs. Formatado: Permite copiar a tabela de reconciliação com números puros (prontos para =SOMA() no Excel) ou formatados com símbolos de moeda.
​🚀 Como Usar
​Execução Direta (Zero Instalação)
​Por seguir uma arquitetura Single-File, você não precisa instalar Node.js, pacotes npm ou configurar bundlers:
