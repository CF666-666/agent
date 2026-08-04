# Phase 3-A: Configurable industrial entity normalization

## Closed-loop goal

Make hypergraph retrieval tolerant of equipment aliases and operational colloquialisms
without changing the original facts or their evidence. The first implementation uses a
configuration-backed alias dictionary rather than an LLM call, so behavior remains
deterministic and can be evaluated independently.

## Design

`IndustrialEntityNormalizer` is the single boundary for canonicalizing index keys.
`IndustrialHyperGraphImpl` applies it both while building the inverted index and when
matching query entities. Hyperedge fields remain unchanged, which means retrieval
results and SSE references still show the exact wording extracted from the source
document.

Aliases are configured at `ragent.hypergraph.entity-normalization.aliases`, where the
map key is an alias and the value is its canonical entity. For example, `风机1号` and
`一号风机` both map to `1号鼓风机`.

## Verification

- alias dictionary behavior: aliases, whitespace, unknown entities, and de-duplication;
- hypergraph retrieval: two query aliases match a canonical equipment/fault edge with a
  match count of two;
- existing document replacement regression remains covered.

## Next closure: bounded relation paths

The hypergraph now exposes a bounded 1–2 hop `RelationPath` API. It starts from
directly matched edges, expands only through indexed non-query bridge entities, avoids
reusing the same edge, and deduplicates paths by their edge pair. A path retains the
ordered hyperedges, bridge entities, score, and therefore each edge's document/chunk
evidence. Search-channel rendering will be connected in a later closure because its
working-tree changes belong to the parallel retrieval work.

## Retrieval rendering closure

`HyperGraphSearchChannel` now consumes `RelationPath` directly and emits one chunk per
path. Its metadata contains the hop count, bridge entities, a readable relation path,
and per-edge document/chunk/version evidence. This keeps graph traversal inside the
hypergraph module while allowing downstream fusion and SSE references to display a
traceable relation path without parsing natural-language text.

## Typed match scoring closure

`HyperEdgeMatchScorer` centralizes field-role weighting for direct hyperedge ranking.
Default weights favor equipment, fault, and parameter matches over generic operating
conditions; deployments may override them under `ragent.hypergraph.entity-weights`.
