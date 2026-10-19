# Contribuindo

[English](CONTRIBUTING.md) | **Português**

Obrigado por ajudar. Este repositório é opinativo de propósito, então o critério para uma mudança é um motivo, não uma preferência.

## Mudando uma regra

1. Abra uma issue primeiro. Diga qual regra, o que quebra quando as pessoas a seguem e o que a regra deveria dizer.
2. Com o acordo feito, abra um PR que atualiza a regra, os exemplos e o checklist juntos. Regra que muda sem os exemplos não é aceita.
3. Nunca enfraqueça uma regra para encaixar um exemplo ruim. Corrija o exemplo.

## Adicionando uma skill

1. Veja a lista de planejadas no README e abra uma issue dizendo qual decisão a skill trata. Uma decisão por skill.
2. Copie [templates/SKILL.template.md](templates/SKILL.template.md) para `skills/<nome>/SKILL.md`. O nome da pasta e o campo `name` precisam ser iguais.
3. Escreva a `description` como "Use when ..." para o agente saber quando carregá-la. A skill é escrita em inglês.
4. Adicione `examples/good/` e `examples/bad/`, cada um com um `Main.java` que usa só o JDK ou um projeto Maven mínimo:
   - compilado para o piso: `<maven.compiler.release>25</maven.compiler.release>`, ou o source launcher com `--source 25`
   - o exemplo bom confere o próprio resultado (sai com 0, ou `mvn verify` passa); o ruim compila
   - pequeno: menos de cerca de 300 linhas de Java, testes incluídos
5. Adicione a skill ao índice **nos dois** arquivos, `README.md` e `README.pt-BR.md`.

## Verificações locais

Rode as duas antes de dar push. O CI roda os mesmos scripts.

```bash
bash scripts/validate.sh       # formato: frontmatter, seções, tamanho, índice do README
bash scripts/test-examples.sh  # todos os exemplos no JDK 25 e 27 em Docker: o bom roda, o ruim compila
```

## Commits e PRs

- Uma skill por PR. Uma mudança lógica por commit.
- Mensagens de commit, em inglês: `skill(errors): add rule on wrapping with operation name`, `docs(readme): update skill index`, `ci: test on JDK 27`.
- Qualquer mudança em um README ou CONTRIBUTING atualiza a versão em inglês ou português no mesmo commit.
- Preencha o template de pull request: o que muda, o que quebra sem a mudança e as fontes primárias.
- Quando conferir ou mudar os fatos de uma skill, coloque essa data em `metadata.verified` no frontmatter dela.
