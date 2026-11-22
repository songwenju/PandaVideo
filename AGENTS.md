# PandaVideo agent guide

## Project boundaries

- Follow [mobile/AGENTS.md](mobile/AGENTS.md) for mobile work.
- Keep [README.md](README.md) focused on the product and user instructions. Put engineering details in agent guidance or development documentation.
- The mobile application connects directly to catalog and playback sources; the frontend/NAS service is a separate application.

## Graphify query entry

Before exploring architecture, dependencies, callers, or change impact, query the existing code graph from the repository root:

```sh
graphify query "PlaybackSession OfflineDownloads" --budget 2000
graphify explain "PlaybackSession"
graphify path "PlaybackSession" "PlayerController" --undirected
graphify affected "PlaybackSession" --depth 2
```

- [graphify-out/graph.json](graphify-out/graph.json): persistent graph with source paths and line locations.
- [graphify-out/graph.html](graphify-out/graph.html): interactive graph.
- [graphify-out/GRAPH_REPORT.md](graphify-out/GRAPH_REPORT.md): communities, hubs, coverage, and diagnostic limitations.
- [graphify-out/coverage.json](graphify-out/coverage.json): indexed files, content hashes, parser warnings, and integrity counts.

Graphify matches symbol text; it does not translate Chinese questions or resolve synonyms. Expand questions using labels actually present in `graph.json`, for example `CatalogPlaybackMatchTest`, `BrowseFilters`, `PlaybackSession`, `OfflineDownloads`, and `DlnaCasting`. Use `source_file` and `source_location` from results to read the relevant source before changing it. If a label matches several nodes, inspect their source paths; this CLI version can misresolve even full IDs. For precise traversal, load `graph.json` with `networkx.readwrite.json_graph.node_link_graph(data, edges="links")` and address the selected node ID directly. Narrow the query or increase `--budget` when output is truncated.

The graph indexes first-party code, configuration supported by Graphify, and tests in `mobile/` and `frontend/`. It excludes generated output, third-party minified libraries, resource files, release packages, and documentation according to [.graphifyignore](.graphifyignore). XML manifests, plist files, and other unsupported formats require direct reading.

The initial Graphify 0.9.64 build reported partial parsing in ten files, including `AppState.kt` and `ContentRepository.kt`; consult the current coverage report. Imported or unresolved references can have no source location. `EXTRACTED` describes a static syntactic relationship; `INFERRED` is a heuristic relationship. Neither proves runtime behavior. A missing edge does not prove that two components are unrelated, especially in Compose, coroutine flows, platform interop, or partially parsed files. The graph is undirected: `path` shows a structural connection, not execution order.

## Capability contract

All capabilities that other changes must not silently alter are registered in [graphify-out/capabilities.json](graphify-out/capabilities.json); [graphify-out/CAPABILITIES.md](graphify-out/CAPABILITIES.md) is the human-readable mirror. The registry records each capability's requirement, implementation files, code anchors, and guard tests; [graphify-out/capabilities.sig](graphify-out/capabilities.sig) detects unaccepted registry edits.

Run the check before and after any code change:

```sh
python3 graphify-out/check_capabilities.py
```

- Adding or intentionally changing a capability requires updating the implementation, its guard tests, `capabilities.json`, and `CAPABILITIES.md`, then explicitly accepting the contract with `python3 graphify-out/check_capabilities.py --accept`.
- Never run `--accept` to silence an unexpected failure, and never delete an anchor or guard test without following the intentional-change flow.
- In multi-window work, a failure naming a capability you did not touch means another window changed it. Reconcile or revert; do not re-sign over it.
- The signature is change detection, not a security boundary. New capabilities are not discovered automatically and must be registered by hand.
- Text anchors do not prove runtime behavior; still run the mobile host tests and platform builds required by [mobile/AGENTS.md](mobile/AGENTS.md).

## Refresh and verify

After changing code, refresh the graph and its report from the repository root:

```sh
graphify update .
"$(cat graphify-out/.graphify_python)" graphify-out/refresh_report.py
graphify export html
graphify query "PlaybackSession" --budget 1000
```

For the first build or a complete rescan:

```sh
graphify extract . --code-only --max-workers 4
"$(head -1 "$(command -v graphify)" | cut -c3-)" graphify-out/refresh_report.py
graphify export html
```

The refresh helper records the interpreter path used by Graphify. If the CLI is unavailable, install the `graphifyy` package with `uv tool install graphifyy`. The local CLI is older than the installed skill; use the actual CLI help for supported flags. Do not overwrite the newer skill by running `graphify install`.

Graphify uses file hashes for incremental extraction; a matching Git commit alone cannot establish freshness when the working tree has edits. Compare `coverage.json` hashes with current source contents when freshness matters. If files were intentionally deleted and Graphify refuses a smaller graph, inspect the deletion and use `graphify update . --force` only for that intentional rebuild.

Graph artifacts are written into the working tree. Keep the portable graph, HTML, report, coverage, and refresh helper available for future agents; interpreter/root sidecars and AST caches are machine-local. Code graph generation does not replace the mobile build/test checks required by `mobile/AGENTS.md`.
