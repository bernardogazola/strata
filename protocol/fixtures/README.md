# Fixtures de protocolo

Transcrições JSONL do protocolo entre o app e o analisador Java, usadas pelos testes dos dois
lados.

O nome segue o formato `<tópico>[.<caso>].jsonl`, em que o tópico é um método ou evento de
`src/protocol/registry.ts`, ou `protocol` para quadros de envelope. Cada linha é um objeto JSON.
Se houver uma linha `"kind": "request"`, ela vem primeiro e é a entrada; as linhas seguintes são a
saída esperada, na ordem em que são emitidas. Nenhuma linha de saída também é uma expectativa
válida.
