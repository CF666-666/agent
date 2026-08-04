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

This closure indexes into the existing process-local `IndustrialHyperGraph` only. Durable storage, re-ingestion replacement, deletion cleanup, and process-start reconstruction require a separate persistence contract and are the next closure; they are not represented as complete yet.
