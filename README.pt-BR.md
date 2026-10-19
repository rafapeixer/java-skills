# java-skills

[English](README.md) | **Português**

**Skills opinativas que ensinam agentes de código a escrever Java atual, seguro e de baixo consumo para serviços backend.**

Agentes de código conhecem a sintaxe do Java. Eles não sabem *qual* jeito de fazer as coisas no JDK e no Spring Boot de hoje. Sozinhos, deixam o heap nos 25% padrão da JVM dentro de um container, colocam virtual threads num pool, chamam o broker dentro de `@Transactional`, usam uma versão do pgJDBC com CVEs conhecidas porque o BOM do Spring Boot fixa essa versão e aceitam corpos JSON de qualquer tamanho.

Cada skill trata de **uma decisão de design**, toma uma posição clara, mostra um exemplo bom e um ruim e dá ao agente um checklist para revisar o que escreveu. Todo exemplo roda no CI no **JDK 25**, o LTS atual, e no **JDK 27**, a versão mais nova.

Funciona com Claude Code, Cursor, Codex e qualquer agente que leia arquivos `SKILL.md`. Repositórios irmãos: [go-skills](https://github.com/rafapeixer/go-skills) e [db-skills](https://github.com/rafapeixer/db-skills).

---

## Instalação

### Plugin do Claude Code (recomendado)

```
/plugin marketplace add rafapeixer/java-skills
/plugin install java-skills@java-skills
```

As skills carregam sozinhas quando a tarefa corresponde, ou pelo nome: `/java-skills:virtual-threads`. Uma nota curta no início da sessão ([`hooks/session-context.md`](hooks/session-context.md)) lista as armadilhas e lembra o agente de carregar a skill certa. As atualizações chegam a cada release.

### Claude Code, copiando os arquivos

```bash
git clone https://github.com/rafapeixer/java-skills.git /tmp/java-skills
mkdir -p .claude/skills
cp -r /tmp/java-skills/skills/* .claude/skills/
```

### CLI universal de skills

```bash
npx skills add rafapeixer/java-skills
```

### Qualquer outro agente

Copie a pasta `skills/` para onde o seu agente carrega skills. Cada skill é uma pasta independente com um `SKILL.md` na raiz.

---

## Skills

> v1, v1.1 e v2 estão prontas: 24 skills.

### v1

| Skill | Use quando | Status |
|---|---|---|
| [project-layout](skills/project-layout/SKILL.md) | Criar um serviço ou adicionar pacotes: pacotes por funcionalidade, classe principal no pacote raiz, package-private por padrão, sem pares Service/Impl | Pronta |
| [exceptions](skills/exceptions/SKILL.md) | Lançar, capturar ou encapsular exceções, tratar InterruptedException ou transformar erros em respostas HTTP (ProblemDetail) | Pronta |
| [timeouts-and-deadlines](skills/timeouts-and-deadlines/SKILL.md) | Colocar timeouts em chamadas bloqueantes (HttpClient, Future.get, JDBC) ou propagar o prazo da requisição para todas as chamadas | Pronta |
| [virtual-threads](skills/virtual-threads/SKILL.md) | Executar trabalho bloqueante em paralelo, ativar virtual threads no Spring Boot ou limitar chamadas simultâneas a uma dependência | Pronta |
| [http-server](skills/http-server/SKILL.md) | Construir um serviço Spring Boot MVC/Tomcat: limite de corpo, erros problem+json, timeouts, probes, graceful shutdown, virtual threads, versionamento de API | Pronta |
| [postgres-hikari-pool](skills/postgres-hikari-pool/SKILL.md) | Configurar o HikariCP para PostgreSQL: tamanho do pool pelo orçamento de conexões, pool fixo, maxLifetime, connectionTimeout curto, virtual threads, ajustes do pgJDBC | Pronta |
| [secure-defaults](skills/secure-defaults/SKILL.md) | Gerar tokens, fazer hash de senhas, desserializar, ler XML, configurar TLS ou ler segredos | Pronta |
| [testing](skills/testing/SKILL.md) | Escrever testes com JUnit/Mockito/Testcontainers, testar código que depende do tempo ou separar testes unitários e de integração | Pronta |

### v1.1

| Skill | Use quando | Status |
|---|---|---|
| [sql-and-transactions](skills/sql-and-transactions/SKILL.md) | Escrever SQL com JDBC/JdbcClient e @Transactional: só parâmetros, inserts em lote, paginação por keyset, autoinvocação do proxy, sem chamadas remotas na transação, retry em 40001/40P01 | Pronta |
| [db-migrations](skills/db-migrations/SKILL.md) | Adicionar migrations Flyway para PostgreSQL: rodar como job separado antes do rollout, nunca editar arquivos aplicados, expand/contract, lock_timeout, CREATE INDEX CONCURRENTLY fora de transação | Pronta |
| [logging-structured](skills/logging-structured/SKILL.md) | Adicionar logs num serviço Java/Spring Boot: JSON ECS/Logstash, placeholders SLF4J e addKeyValue, request id no MDC, sem segredos, logar uma vez, níveis | Pronta |
| [ci-guardrails](skills/ci-guardrails/SKILL.md) | Escrever um pipeline de CI ou a seção build do pom: wrapper do Maven com checksum, enforcer, -Xlint:all com failOnWarning, dependency-check que pode falhar, actions fixadas por SHA | Pronta |
| [container-runtime-tuning](skills/container-runtime-tuning/SKILL.md) | Escrever o Dockerfile ou manifesto Kubernetes de um serviço Java: heap com MaxRAMPercentage=75 em vez de -Xmx, suporte a contêiner ligado, limite de CPU, G1 explícito, JAVA_TOOL_OPTIONS com ENTRYPOINT em forma exec, usuário não-root | Pronta |
| [overload-protection](skills/overload-protection/SKILL.md) | Decidir o que o serviço faz acima da capacidade, adicionar um limite de concorrência ou criar um executor para requisições ou tarefas em segundo plano | Pronta |
| [outbound-http-client](skills/outbound-http-client/SKILL.md) | Chamar outro serviço por HTTP (HttpClient, RestClient), adicionar retentativas ou ler corpos de resposta | Pronta |
| [java-version-policy](skills/java-version-policy/SKILL.md) | Escolher a versão do Java para compilar, testar ou fazer deploy: piso --release 25, testes em 25 e 27, runtime LTS atualizado, sem recursos em preview | Pronta |

### v2

| Skill | Use quando | Status |
|---|---|---|
| [observability-otel](skills/observability-otel/SKILL.md) | Adicionar traces, métricas ou correlação de logs com OpenTelemetry a um serviço Java/Spring Boot: agente OTel por padrão, amostragem parent-based, nomes de span e tags de métrica limitados | Pronta |
| [profiling-jfr](skills/profiling-jfr/SKILL.md) | Um serviço Java está lento, gasta CPU ou memória demais ou fixa virtual threads: medir antes com gravação JFR sempre ligada, extrair com jcmd JFR.dump e ler com jfr view | Pronta |
| [fast-startup-aot-cache](skills/fast-startup-aot-cache/SKILL.md) | Um serviço Java ou Spring Boot sobe devagar: treinar um AOT cache (-XX:AOTCacheOutput / -XX:AOTCache) na imagem de runtime com o mesmo JDK e os mesmos jars; native image ou CRaC só com um motivo | Pronta |
| [background-jobs-outbox](skills/background-jobs-outbox/SKILL.md) | Uma escrita precisa publicar um evento ou disparar trabalho em background: linha de outbox na mesma transação, relay com FOR UPDATE SKIP LOCKED, consumidores idempotentes | Pronta |
| [json-jackson3](skills/json-jackson3/SKILL.md) | Ler ou escrever JSON com Jackson 3 ou migrar do Jackson 2: um JsonMapper compartilhado, limites de leitura, propriedades desconhecidas estritas, records, sem type id por nome de classe | Pronta |
| [scoped-values](skills/scoped-values/SKILL.md) | Propagar contexto da requisição (request id, tenant, usuário) pela pilha de chamadas ou substituir um ThreadLocal | Pronta |
| [null-safety-jspecify](skills/null-safety-jspecify/SKILL.md) | Anotar nulidade, escolher entre null e Optional, migrar do JSR 305 ou configurar o NullAway | Pronta |
| [mcp-server-java](skills/mcp-server-java/SKILL.md) | Escrever um servidor MCP em Java com o SDK oficial ou Spring AI: ferramentas estreitas, schemas de entrada estritos, nada no stdout com STDIO, timeouts, checagem de audience OAuth no HTTP | Pronta |

---

## Muda o que os agentes escrevem?

Medido com `claude plugin eval` ([`evals/`](evals/)): cada tarefa roda com e sem o plugin, **10 vezes por lado**, e graders por regex conferem o arquivo que o agente escreve. Outubro de 2026.

| | Sem plugin | Com plugin |
|---|---|---|
| **Todas as 11 tarefas, 110 execuções** | **58 / 110 (53%)** | **107 / 110 (97%)** |

Onde o agente errou sem o plugin:

| Tarefa | Sem | Com | A falha |
|---|---|---|---|
| `pom.xml` de um serviço Spring Boot 4.1 com PostgreSQL | 0 / 10 | **10 / 10** | Ficou com o pgJDBC 42.7.13 que o BOM do Boot fixa, que tem duas CVEs corrigidas na 42.7.14 |
| Pool do HikariCP para 6 réplicas (8 no deploy) e 200 conexões | 0 / 10 | **10 / 10** | `minimumIdle` igual ao máximo e o `connectionTimeout` padrão de 30 s, então a sobrecarga vira fila em vez de falhar rápido |
| Setup do Maven para Mockito com classes final no JDK 25 | 0 / 10 | **10 / 10** | JUnit 5 em vez de JUnit 6 |
| `pom.xml` de um serviço novo no piso do Java | 0 / 10 | **10 / 10** | Sem `release 25` e sem regra do enforcer para a versão do Java |
| Dockerfile de um serviço Java num pod de 1 CPU / 1 GiB | 3 / 10 | **10 / 10** | Não nomeou o G1, então o JDK 25 roda Serial GC nesse container sem avisar |
| Migration Flyway com `CREATE INDEX CONCURRENTLY` | 5 / 10 | **7 / 10** | Deixou o lock transacional do Flyway ligado, então o índice espera o lock do próprio Flyway |

Fan-out com virtual threads, prazo da requisição, Jackson 3 com entrada não confiável, limite do corpo JSON e outbox transacional deram 10 / 10 dos dois lados: o modelo já acerta isso, e as skills mantêm assim. A skill carregou em 110 de 110 execuções.


**Sob carga:** o [BENCHMARKS.pt-BR.md](BENCHMARKS.pt-BR.md) roda o serviço que o agente escreveu, com e sem estas skills, em containers do tamanho de um t4g.micro da AWS, 3 vezes por cenário. Sem as skills ele caiu na sobrecarga (96,5% de erro a 4.000 req/s) e não se recuperou; gerado com as java-skills v3, segurou 5.000 req/s com 0% de erro, recusou o excesso a 4.000 req/s com 1,6% de 503 rápidos, se recuperou na hora e empatou com uma versão ajustada à mão com JFR. Atender 10.000 req/s custa US$ 112 por mês em EC2 em vez de US$ 178.

---

## Serviço de referência

[`reference/orders-api/`](reference/orders-api/) é o serviço que o agente escreveu com estas skills (a v3 do [BENCHMARKS.pt-BR.md](BENCHMARKS.pt-BR.md)), com 49 testes contra um PostgreSQL de verdade e um serviço de preços simulado, compilado no CI com JDK 25 e 27. Spring Boot 4.1 com Java 25, virtual threads com limite de requisições em andamento na entrada, pool do HikariCP dimensionado pelo orçamento de conexões, um comando SQL por leitura, prazo em toda chamada e um Dockerfile para container de 1 GiB. Clone como ponto de partida; o README dele diz de qual skill vem cada configuração e o que medir de novo antes de mudar um limite.

---

## Como é uma skill

Todo `SKILL.md` tem o mesmo formato:

1. **The decision**: qual escolha a skill trata
2. **The rule**: a posição do repositório, em regras numeradas de uma linha
3. **Why**: o motivo e o custo de fazer diferente
4. **Good example**: código que roda, em `examples/good/`
5. **Bad example**: o erro real, em `examples/bad/`, com cada erro comentado
6. **Checklist**: itens sim/não que o agente aplica ao revisar
7. **When not to apply**: as exceções, porque toda regra tem

---

## Princípios

- **Uma decisão por skill.** Nada de "boas práticas" genéricas.
- **Uma posição, com motivos.** "Depende" só vale seguido de "do quê, e o que fazer em cada caso".
- **Use o que o runtime já dá.** Virtual threads, ergonomia que respeita o container, AOT cache, TLS 1.3 e, no JDK 27, troca de chaves pós-quântica por padrão. A maioria dos bugs de produção vem de desligar um default bom ou de nunca ligá-lo.
- **Limite tudo.** Timeouts, tamanhos de pool, filas, concorrência, corpos de requisição.
- **Java atual.** Os exemplos compilam com `--release 25` (o LTS mais novo) e rodam no JDK 25 e 27. Recursos em preview aparecem como preview, nunca como padrão. Os fatos vêm só de openjdk.org, docs.oracle.com, docs.spring.io e da documentação de cada biblioteca.
- **Os exemplos rodam.** Os bons rodam as próprias checagens ou `mvn verify`, muitos contra um PostgreSQL de verdade no Testcontainers; os ruins compilam.

---

## Contribuindo

Discorda de uma regra? Abra uma issue e defenda. As regras mudam quando alguém mostra o que quebra, não quando alguém prefere outra coisa.

Quer adicionar uma skill? Comece por [templates/SKILL.template.md](templates/SKILL.template.md), siga o [CLAUDE.md](CLAUDE.md), uma skill por PR. Veja o [CONTRIBUTING.pt-BR.md](CONTRIBUTING.pt-BR.md).

---

## Licença

MIT
