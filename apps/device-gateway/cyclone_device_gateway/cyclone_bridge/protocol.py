from __future__ import annotations

ALLOWED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "trust.rotate", "trust.revoke",
    "bridge.status",
    "session.list", "session.start", "session.status", "session.pause", "session.continue",
    "session.handoff", "session.stop", "session.snapshot",
    "observe.semantic", "observe.page_debug", "ui.search", "ui.element",
    "app_graph.get", "brain.recall", "action.execute", "teach.start", "teach.status",
    "teach.stop", "debug.snapshot", "pair.begin", "pair.complete", "pair.qr.complete", "pair.revoke",
    "manual.execute", "clipboard.get", "clipboard.set",
    "skill.compile", "skill.run", "skill.match",
    "atlas.places", "atlas.get", "atlas.diff",
    "mapping.start", "mapping.pause", "mapping.stop", "mapping.status",
    "secrets.slots", "secrets.request",
    "ask.start", "ask.status",
    "apps.list",
    "runs.list", "runs.get", "runs.mark",
    "atlas.versions", "scenarios.list", "knowledge.get", "atlas.here",
    # Cyclone Lab: measured Mind missions (start, watch, answer as the owner, read back).
    "lab.start", "lab.status", "lab.answer", "lab.record",
    # Cyclone Marketplace: the phone's store of recipes and connections.
    "market.catalog", "market.install", "market.remove", "market.run",
}
UNAUTHENTICATED_OPS = {
    "trust.negotiate", "trust.begin", "trust.complete", "trust.session.begin", "trust.session.complete",
    "pair.begin", "pair.complete", "pair.qr.complete",
}
