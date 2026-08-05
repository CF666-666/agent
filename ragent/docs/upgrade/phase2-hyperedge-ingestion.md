# Phase 2-A: Hyperedge extraction in the ingestion pipeline

## Closed-loop goal

Add `hyperedge_extract` as an ingestion node after `chunker`. It extracts industrial N-ary facts per chunk, attaches document and chunk-level evidence, then adds all extracted edges to the existing in-memory inverted index in one operation.

## Evidence contract

Each `HyperEdge` produced by this node records:

- `sourceDocument`: source location, filename, or ingestion fallback identifier;
- `sourceChunkId` and `sourceChunkIndex`: stable per-document evidence position;
- `sourcePage`: propagated from parser/chunk metadata when available;
- `documentVersion`: propagated from ingestion metadata when available.

The node fails if no chunks are available. Runtime extraction failures fail the node before any extracted edges are indexed. Empty extraction is successful and observable through the node result count.

## Deliberate boundary

This first closure indexes into the existing process-local `IndustrialHyperGraph` only. Durable storage, re-ingestion replacement, deletion cleanup, and process-start reconstruction are handled by the following persistence closure.

## Persistence closure design

`HyperEdgeDocumentStore` is the seam between ETL and durable storage. Its two operations replace all facts for one document (including an empty extraction) and load active facts at startup. The PostgreSQL adapter physically removes the old document rows and writes the replacement in one transaction; the node then replaces the corresponding in-memory facts. Startup prefers persisted edges and uses the legacy JSONL loader only as a fallback.

## Recommended industrial-document topology

There is intentionally no implicit default pipeline: an ingestion task always names a
pipeline, so different document types can keep their own parser and indexing policy.
For industrial PDF, scanned document, and drawing ingestion, configure the following
ordered path:

```text
fetcher -> parser -> chunker -> hyperedge_extract -> indexer
```

`hyperedge_extract` consumes the chunks created by `chunker`; it persists document
facts and refreshes the in-memory hyperedge index before `indexer` writes the text
vectors. The corresponding pipeline nodes use `fetcher`, `parser`, `chunker`,
`hyperedge_extract`, and `indexer` as their `nodeType` values, with default edges in
the order above. The execution test protects the critical inner segment
`chunker -> hyperedge_extract -> indexer` from route-order regressions.

## Knowledge-document deletion cleanup

The existing knowledge-document delete transaction now removes the matching
`ingestion:{docId}` hyperedges after vector cleanup. A dedicated lifecycle seam first
replaces durable facts with an empty document set and then refreshes the in-memory
index, so deleted facts can no longer appear in relation retrieval. This hook applies
to documents processed through the configurable ingestion pipeline; documents using
the legacy chunk-only path have no hyperedges to remove.

## Startup recovery invariant

An available persistent store is authoritative even when it contains zero active
hyperedges. JSONL is used only when the persistent-store read fails, preventing a
deleted document or an intentionally empty re-ingestion from being resurrected at
process startup.

## Extraction failure invariant

Only a complete, trimmed LLM JSON array (`[]`) represents a valid empty
extraction. LLM transport failures, null responses, explanatory text, malformed
JSON and non-object array elements fail the whole document extraction so the node
cannot replace a document's existing hyperedges with a partial or empty result.
Likewise, a non-empty chunk list with no non-blank content fails before document
replacement; an explicit `[]` is accepted only after at least one chunk reaches
the extractor.

## Knowledge-document identity invariant

Knowledge-document pipeline execution and deletion both derive the hyperedge
owner key from `HyperEdgeDocumentIdentity.forKnowledgeDocument(docId)`. The
stable `ingestion:{docId}` key is separate from a mutable filename or source URL,
so document deletion removes the same facts that ingestion created.

## Closure verification

- Reactor compile: `mvn -pl bootstrap -am -DskipTests compile`;
- unit and retrieval regression: 7 tests, 0 failures;
- PostgreSQL replacement and JSONB evidence restoration: 1 integration test, 0 failures.
- configured `chunker -> hyperedge_extract -> indexer` route execution: 1 test, 0 failures.
