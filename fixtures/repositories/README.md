# Repositórios de fixture

Repositórios Java pequenos usados nos testes do analisador. Cada um traz um `expected.json` com os
valores esperados, calculados à mão, e um `cloc-golden.txt` com a contagem de linhas medida pelo
cloc.

O `.git` de cada fixture é gerado, não versionado. Depois de clonar o projeto, rode:

```bash
bash scripts/make-fixtures.sh
```

O gerador é determinístico: mesma árvore, mesmos autores, mesmas datas e, portanto, os mesmos IDs
de commit em qualquer máquina. Os IDs ficam em `commits.txt` e são conferidos por
`bash scripts/verify-fixtures.sh --expect-git`.
