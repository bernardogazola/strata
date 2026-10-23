#!/usr/bin/env python3

import json
import sys
from fractions import Fraction

DAMPING = Fraction(17, 20)

EXIT_OK = 0
EXIT_MISMATCH = 1
EXIT_USAGE = 2

USAGE = "uso: python3 -I scripts/pagerank-oracle.py (<grafo.json> | --self-test)"


class OracleError(Exception):
    """A solução não é o ponto fixo, ou o sistema é singular: um defeito do oráculo."""


class InputError(Exception):
    """A entrada não descreve um grafo dirigido simples."""


def teleport_weights(out_degree):
    return [(1 - DAMPING) if degree > 0 else Fraction(1) for degree in out_degree]


def degrees(n, edges):
    out_degree = [0] * n
    incoming = [[] for _ in range(n)]
    for source, target in edges:
        out_degree[source] += 1
        incoming[target].append(source)
    return out_degree, incoming


def check_fixed_point(x, edges):
    n = len(x)
    out_degree, incoming = degrees(n, edges)
    weights = teleport_weights(out_degree)
    r = sum(weight * value for weight, value in zip(weights, x)) / n
    for i in range(n):
        expected = r + DAMPING * sum(x[w] / out_degree[w] for w in incoming[i])
        if x[i] != expected:
            raise OracleError(f"não é ponto fixo no vértice {i}: {x[i]} != {expected}")
        if x[i] <= 0:
            raise OracleError(f"rank não positivo no vértice {i}: {x[i]}")
    if sum(x) != 1:
        raise OracleError(f"os ranks somam {sum(x)}, e não 1")


def solve(n, edges):
    out_degree, incoming = degrees(n, edges)
    weights = teleport_weights(out_degree)
    rows = []
    for i in range(n):
        row = [Fraction(0)] * (n + 1)
        row[i] += 1
        for w in incoming[i]:
            row[w] -= DAMPING / out_degree[w]
        for w in range(n):
            row[w] -= weights[w] / n
        rows.append(row)
    rows[n - 1] = [Fraction(1)] * n + [Fraction(1)]

    for column in range(n):
        pivot = next((r for r in range(column, n) if rows[r][column] != 0), None)
        if pivot is None:
            raise OracleError(f"sistema singular na coluna {column}")
        rows[column], rows[pivot] = rows[pivot], rows[column]
        head = rows[column][column]
        rows[column] = [value / head for value in rows[column]]
        for r in range(n):
            factor = rows[r][column]
            if r != column and factor != 0:
                rows[r] = [a - factor * b for a, b in zip(rows[r], rows[column])]

    x = [rows[i][n] for i in range(n)]
    check_fixed_point(x, edges)
    return x


def read_graph(path):
    with open(path, encoding="utf-8") as handle:
        data = json.load(handle)
    if not isinstance(data, dict):
        raise InputError("o nível superior deve ser um objeto com 'vertices' e 'edges'")
    vertices = data.get("vertices")
    edges = data.get("edges")
    if not isinstance(vertices, list) or not vertices:
        raise InputError("'vertices' deve ser uma lista não vazia de ids")
    if not isinstance(edges, list):
        raise InputError("'edges' deve ser uma lista de pares [origem, destino]")
    index = {}
    for vertex in vertices:
        if not isinstance(vertex, str) or not vertex:
            raise InputError(f"o id de um vértice deve ser uma string não vazia: {vertex!r}")
        if vertex in index:
            raise InputError(f"vértice listado duas vezes: {vertex}")
        index[vertex] = len(index)
    pairs = []
    seen = set()
    for edge in edges:
        if not isinstance(edge, list) or len(edge) != 2:
            raise InputError(f"uma aresta deve ser um par [origem, destino]: {edge!r}")
        source, target = edge
        if source not in index or target not in index:
            raise InputError(f"aresta para um vértice desconhecido: {edge!r}")
        if source == target:
            raise InputError(f"laço: {source} (uma linha do vértice para ele mesmo não é aresta)")
        pair = (index[source], index[target])
        if pair in seen:
            raise InputError(f"aresta listada duas vezes: {source} -> {target}")
        seen.add(pair)
        pairs.append(pair)
    return vertices, pairs


def print_block(title, vertices, x):
    print(f"# {title}")
    for vertex, value in zip(vertices, x):
        print(f"{vertex}\t{value.numerator}/{value.denominator}\t{float(value)!r}")


def run(path):
    vertices, edges = read_graph(path)
    n = len(vertices)
    forward = solve(n, edges)
    reversed_ranks = solve(n, [(target, source) for source, target in edges])
    print_block("forward: G as extracted (A->B = A depends on B), the analyzer's pagerank",
                vertices, forward)
    print_block("reversed: G_rev, the analyzer's pagerankOut", vertices, reversed_ranks)


def self_test():

    cases = (
        ("estrela, direto", 3, [(0, 1), (0, 2)],
         [Fraction(20, 77), Fraction(57, 154), Fraction(57, 154)]),
        ("estrela, invertido", 3, [(1, 0), (2, 0)],
         [Fraction(27, 47), Fraction(10, 47), Fraction(10, 47)]),
        ("vértice único", 1, [], [Fraction(1)]),
    )
    failures = 0
    for name, n, edges, expected in cases:
        got = solve(n, edges)
        status = "ok" if got == expected else "falha"
        failures += got != expected
        print(f"{status}\t{name}\tesperado {list(map(str, expected))}\tobtido {list(map(str, got))}")

    perturbed = [Fraction(20, 77) + Fraction(1, 10**9), Fraction(57, 154), Fraction(57, 154)]
    try:
        check_fixed_point(perturbed, [(0, 1), (0, 2)])
        failures += 1
        print("falha\tchecagem de ponto fixo\taceitou um vetor que não é o ponto fixo")
    except OracleError:
        print("ok\tchecagem de ponto fixo\trecusa um vetor que não é o ponto fixo")
    return EXIT_MISMATCH if failures else EXIT_OK


def main(argv):
    if len(argv) != 2:
        print(USAGE, file=sys.stderr)
        return EXIT_USAGE
    if argv[1] == "--self-test":
        return self_test()
    try:
        run(argv[1])
    except (OSError, ValueError, InputError) as error:
        print(f"erro: {error}", file=sys.stderr)
        return EXIT_USAGE
    except OracleError as error:
        print(f"defeito do oráculo: {error}", file=sys.stderr)
        return EXIT_MISMATCH
    return EXIT_OK


if __name__ == "__main__":
    sys.exit(main(sys.argv))
