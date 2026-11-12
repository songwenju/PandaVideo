"""Refresh Graphify's report, labels, and coverage without an LLM or source edits."""

from collections import Counter
from datetime import datetime, timezone
import hashlib
from importlib.metadata import version
import json
from pathlib import Path
import re
import sys

from networkx.readwrite import json_graph
from graphify.analyze import god_nodes, surprising_connections, suggest_questions
from graphify.cluster import score_all
from graphify.detect import detect
from graphify.diagnostics import diagnose_extraction
from graphify.extract import _DISPATCH, extract
from graphify.report import generate


ROOT = Path(__file__).resolve().parent.parent
OUT = ROOT / "graphify-out"
data = json.loads((OUT / "graph.json").read_text(encoding="utf-8"))
graph = json_graph.node_link_graph(data, edges="links")
communities = {}
for node_id, node in graph.nodes(data=True):
    communities.setdefault(int(node.get("community", -1)), []).append(node_id)

# Name communities from their actual source files, rather than inventing domain
# relationships. Weight by number of located declarations; prefer the file node
# for tiny communities consisting mostly of unresolved imports.
labels = {}
for community_id, members in communities.items():
    files = Counter(
        graph.nodes[node_id].get("source_file")
        for node_id in members
        if graph.nodes[node_id].get("source_file")
        and graph.nodes[node_id].get("source_location")
    )
    if not files:
        files = Counter(
            graph.nodes[node_id].get("source_file")
            for node_id in members
            if graph.nodes[node_id].get("source_file")
        )
    stem = Path(files.most_common(1)[0][0]).stem if files else "External References"
    stem = re.sub(r"([a-z0-9])([A-Z])", r"\1 \2", stem).replace("_", " ").replace("-", " ")
    labels[community_id] = stem + " Symbols"

for node in data["nodes"]:
    node["community_name"] = labels[int(node.get("community", -1))]

detection = detect(ROOT)
code_files = [Path(path) for path in detection["files"]["code"]]
detection["files"] = {"code": [str(path) for path in code_files]}
detection["total_files"] = len(code_files)
detection["total_words"] = sum(
    len(path.read_text(encoding="utf-8", errors="replace").split()) for path in code_files
)

# Sequential extraction avoids multiprocessing spawn at module scope on macOS.
# This pass normally reuses the AST cache populated by `graphify update`.
extraction = extract(code_files, cache_root=ROOT, root=ROOT, parallel=False)
warnings = []
# Re-parse current files for warnings: cache folders can retain older snapshots,
# and file-node-only parse failures need not have a source_file in cached nodes.
for path in code_files:
    parser = _DISPATCH.get(path.suffix)
    if parser is None:
        continue
    parsed = parser(path)
    error = parsed.get("parse_errors")
    if error and (len(parsed.get("nodes", [])) <= 1 or error.get("multiline_error")):
        warnings.append({"source_file": str(path.relative_to(ROOT)), **error})

health = diagnose_extraction(data, directed=bool(data.get("directed")), root=str(ROOT))
raw_health = diagnose_extraction(extraction, directed=bool(data.get("directed")), root=str(ROOT))
coverage = {
    "generated_at": datetime.now(timezone.utc).isoformat(),
    "graphify_version": version("graphifyy"),
    "mode": "code-only",
    "code_file_count": len(code_files),
    "parse_warnings": sorted(warnings, key=lambda warning: warning["source_file"]),
    "health": health,
    "raw_health": raw_health,
    "files": {
        str(path.relative_to(ROOT)): hashlib.sha256(path.read_bytes()).hexdigest()
        for path in sorted(code_files)
    },
}

cohesion = score_all(graph, communities)
gods = god_nodes(graph)
surprises = surprising_connections(graph, communities)
questions = suggest_questions(graph, communities, labels)
report = generate(
    graph, communities, cohesion, labels, gods, surprises, detection,
    {"input": 0, "output": 0}, str(ROOT), suggested_questions=questions,
    built_at_commit=data.get("built_at_commit"),
)
report += "\n## Coverage and Static Analysis Limitations\n\n"
report += (
    "- Code-only extraction; documentation, images, binaries, and unsupported "
    "XML/plist formats are not indexed.\n"
    "- Community names are derived from their dominant source file.\n"
    "- Imported/unresolved nodes can lack source locations. INFERRED edges are "
    "heuristics; missing edges do not establish absence of a runtime relationship.\n"
    "- The graph is undirected; structural paths are not ordered execution traces.\n"
    "- Git commit identity excludes uncommitted edits. Check source SHA-256 hashes "
    "in coverage.json for snapshot freshness.\n"
    "- graphify-out/ was written into the working tree; portable outputs can be "
    "shared, while AST caches and machine-local sidecars are ignored.\n\n"
)
report += f"Parser warnings: {len(warnings)} files may be only partially extracted.\n\n"
for warning in coverage["parse_warnings"]:
    report += (
        f"- `{warning['source_file']}`: first parser error at "
        f"L{warning.get('first_error_line', '?')}.\n"
    )
report += (
    f"\nIntegrity diagnostics: {health.get('dangling_endpoint_edges', 0)} dangling "
    f"edges, {health.get('missing_endpoint_edges', 0)} missing-endpoint edges, "
    f"{health.get('self_loop_edges', 0)} self-loops, "
    f"{raw_health.get('undirected_same_endpoint_collapsed_edges', 0)} same-endpoint "
    "edge collisions in the raw extraction. Collisions can combine distinct "
    "static relationships in the exported graph; inspect source for full detail.\n"
)

for name, payload in (
    ("graph.json", data),
    (".graphify_labels.json", {str(key): value for key, value in labels.items()}),
    (".graphify_analysis.json", {
        "communities": {str(key): value for key, value in communities.items()},
        "cohesion": {str(key): value for key, value in cohesion.items()},
        "gods": gods,
        "surprises": surprises,
        "questions": questions,
        "tokens": {"input": 0, "output": 0},
    }),
    ("coverage.json", coverage),
):
    (OUT / name).write_text(
        json.dumps(payload, indent=2, ensure_ascii=False) + "\n", encoding="utf-8"
    )
(OUT / "GRAPH_REPORT.md").write_text(report, encoding="utf-8")
(OUT / ".graphify_python").write_text(sys.executable, encoding="utf-8")
print(
    f"Report verified: {len(code_files)} files, {graph.number_of_nodes()} nodes, "
    f"{graph.number_of_edges()} edges, {len(warnings)} parser warnings"
)
