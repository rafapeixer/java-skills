# Benchmarks

[English](BENCHMARKS.md) | **Português**

O mesmo serviço, escrito por um agente de código a partir do mesmo prompt, com e sem as java-skills, e depois ajustado à mão com profiler para descobrir o que faltava nas skills. Medido sob carga em containers do tamanho de um t4g.micro da AWS, 3 vezes por cenário. Outubro de 2026.

## O que foi medido

- **O serviço:** `orders-api`, a especificação do [benchmark do go-skills](https://github.com/rafapeixer/go-skills/blob/main/benchmarks/orders-api/SPEC.md): criar e ler pedidos com itens no PostgreSQL, uma lista paginada por cliente e uma cotação que chama um serviço de preços "normalmente ~20 ms, às vezes bem mais lento". 8 réplicas dividem um orçamento de 80 conexões no banco.
- **O prompt:** [`benchmarks/orders-api/generate/`](benchmarks/orders-api/generate/orders-api/prompt.md), Java 25 e Spring Boot 4.1 com virtual threads, JDBC e HikariCP. O agente também escreve o Dockerfile, porque heap, GC e o usuário do container fazem parte do que as skills ensinam.
- **O harness:** o [`harness/`](https://github.com/rafapeixer/go-skills/tree/main/benchmarks/orders-api/harness) do go-skills. Todo container é limitado a 2 vCPU e 1 GiB, um teste de contrato precisa passar antes de qualquer carga, e cada execução começa com um banco novo e populado. Cada número é a mediana de 3 execuções; as tabelas completas trazem a faixa.
- **As versões** ([`benchmarks/orders-api/impl/`](benchmarks/orders-api/impl/)):

| Versão | Como foi feita |
|---|---|
| Java sem skills | Gerada pelo agente sem plugin |
| Java com java-skills v1 | Gerada com a primeira versão das skills |
| Java ajustado à mão | A v1 ajustada à mão com JFR, uma mudança medida por vez ([TUNING.md](benchmarks/orders-api/TUNING.md)) |
| Java com java-skills v2 | Gerada depois que as lições do ajuste entraram nas skills |
| **Java com java-skills v3** | Gerada depois que a regressão da v2 foi corrigida nas skills; agora é o [serviço de referência](reference/orders-api/) |

## Resultados

| Cenário | Sem skills | Skills v1 | Ajustado à mão | Skills v2 | **Skills v3** |
|---|---|---|---|---|---|
| Capacidade dentro do SLO, JVM fria | 3.000 req/s | 2.500 | 3.000 | 3.000 | **3.000** |
| Capacidade dentro do SLO, JVM aquecida | 3.000 req/s | 0 (falha na primeira fase, fria) | 5.000 | 4.000 | **5.000** |
| Sobrecarga a 4.000 req/s: erros, p50 | 96,5%, 4,6 s | 26,7%, 55 ms | 1,2%, 1,0 ms | 94,3%, 3,2 s | **1,6%, 1,1 ms** |
| De volta a 800 req/s depois da sobrecarga | 28% de erro, não se recupera | 0% | 0% | 21% de erro | **0%** |
| JVM aquecida a 5.000 req/s: erros | 99,5% | 39,5% | 0% | 94,4% | **0%** |
| JVM aquecida a 6.000 req/s: erros | 99,7% | 50,4% | 0% | 99,1% | 19,4% |
| Serviço de preços lento (1,5 s): p99 | 2,0 s | 1,0 s | 1,0 s | 1,0 s | **0,8 s** |
| CPU por requisição a 3.000 req/s | 0,61 ms | 0,64 ms | 0,35 ms | 0,42 ms | **0,38 ms** |
| Pico de memória por réplica na sobrecarga | 697 MiB | 319 MiB | 687 MiB | 1.016 MiB (sem memória) | **394 MiB** |
| Conexões no banco, 8 réplicas | 65 | 41 | 65 | 65 | 65 |

As conexões no banco incluem a sessão de amostragem do próprio harness.

## O que mostra

- **Sem as skills, o serviço cai e não levanta.** Acima da capacidade ele enfileira toda requisição: 96,5% de erro a 4.000 req/s, latência mediana de 4,6 s e 28% de erro mesmo depois que a carga voltou ao normal.
- **As primeiras skills corrigiram a forma de falhar, não o custo.** A v1 recusava o excesso na hora e se recuperava, mas gastava mais CPU por requisição que a versão sem skills, e o pool de 5 conexões (dimensionado para 100% de surge no deploy) virou a fila.
- **O ajuste manual achou o que faltava.** Um comando por leitura em vez de uma transação com duas queries, um comando para criar o pedido com os itens, executor de virtual threads no `HttpClient` do JDK, pool dimensionado pelo surge real de 25% do Kubernetes, heap fixo com G1 e fila de accept maior no Tomcat cortaram 45% da CPU por requisição e levaram a capacidade aquecida de 0 para 5.000 req/s. Cada mudança, com a evidência do JFR, está no [TUNING.md](benchmarks/orders-api/TUNING.md).
- **A v2 regrediu, e a regressão virou regra.** Com as lições nas skills, o agente acertou a CPU, mas a skill `http-server` mandava limitar a concorrência por dependência em vez do pool de threads. Com virtual threads nada mais limita as requisições em andamento, então a 4.000 req/s o heap encheu e a JVM morreu com `OutOfMemoryError`. A skill agora exige um limite de requisições em andamento na entrada.
- **A v3, gerada só a partir das skills, empata com a ajustada à mão.** Mesma capacidade aquecida (5.000 req/s), os mesmos 0% de erro a 5.000, mais consistente na sobrecarga (1,5–1,7% de erro entre as execuções, contra 0–18,8% da ajustada à mão) e 43% menos memória. Ela só fica atrás da versão ajustada a 6.000 req/s, onde as 2 vCPU acabam.

## Custo: 10.000 req/s durante um mês

O mesmo modelo do go-skills: cada instância planejada a 70% da capacidade aquecida medida, no mínimo 2 instâncias, T4g em modo unlimited (preço por hora mais US$ 0,04 por vCPU-hora acima do baseline), 730 horas. Banco: RDS PostgreSQL db.t4g.micro Single-AZ com 20 GB gp3. O pico de memória de todas as versões Java cabe num t4g.micro, menos a v2.

| | Instâncias (t4g.micro) | Conexões no banco | EC2, us-east-1 | EC2, sa-east-1 | **Total com RDS, EUA** | **Total com RDS, Brasil** |
|---|---|---|---|---|---|---|
| Java sem skills | 5 | 40 | US$ 178,12 | US$ 196,37 | **US$ 263,28** | **US$ 296,75** |
| Java com java-skills v1 | 6 | 48 | US$ 187,46 | US$ 209,36 | US$ 272,62 | US$ 309,74 |
| Java ajustado à mão | 3 | 24 | US$ 102,49 | US$ 113,44 | US$ 187,65 | US$ 213,82 |
| **Java com java-skills v3** | **3** | **24** | **US$ 111,84** | **US$ 122,79** | **US$ 197,00** | **US$ 223,17** |
| Go com go-skills (referência) | 2 | 16 | US$ 75,63 | US$ 82,93 | US$ 160,79 | US$ 183,31 |

Com as java-skills v3, a frota Java custa **37% menos em EC2** que a versão sem skills, e **25% menos contando o banco**. O Go com go-skills, do [benchmark do go-skills](https://github.com/rafapeixer/go-skills/blob/main/BENCHMARKS.pt-BR.md), continua mais barato: o teto do Java em 2 vCPU é a CPU, perto de 6.000 req/s a cerca de 0,35 ms por requisição.

## Limites

- Um host roda o gerador de carga e todos os containers; os containers têm CPU e memória limitadas, e o host é Docker Desktop em x86, não Graviton.
- Uma geração por versão: outra execução do agente escreve outro código. A avaliação de 11 tarefas no [README](README.pt-BR.md) mede as skills com 10 execuções por tarefa.
- As rampas param em 3.000 req/s (fria) e 8.000 req/s (aquecida), então capacidades acima disso não foram medidas.
- O modelo de custo usa preços de lista de outubro de 2026 e não inclui o load balancer e a transferência de dados, que são iguais para todas as versões.
- Sete das 18 execuções de "Java sem skills" não subiram (falha de DNS ao construir a imagem): uma em capacidade, uma em 8 réplicas, uma em sobrecarga, duas em dependência lenta e duas em carga alta. Essas medianas usam as 2 ou 1 execuções que terminaram; o cenário de JVM aquecida tem as 3.
