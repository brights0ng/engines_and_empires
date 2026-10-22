# Written by ChatGPT.
"""Verify dry-house numerical results against the pre-fluid response archive."""
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parent

if __name__ == "__main__":
    old = json.loads((ROOT.parent / "air-response-2026-10-20" / "responses.json").read_text())
    scalar_errors, field_errors, rows = [], [], []
    for i, name in enumerate(("sealed", "window", "missing-wall")):
        raw = json.loads((ROOT / f"dry-{i}.json").read_text())
        assert raw["complete"] and len(raw["results"]) == 1
        r = raw["results"][0]
        assert r["opening"] == name and not r["firstFailure"]
        assert r["physicalStep"] == .5 and r["responseScale"] == 1
        assert r["acceptedSteps"] == 1360 and r["completedPhysicalSeconds"] == r["advancedPhysicalSeconds"] == 680
        reference = next(p for p in old["results"] if p["opening"] == name and p["responseScale"] == 1)
        for key in ("flowSettings", "thermalSettings", "flowKernel", "thermalTraversal", "cachedSamplingOrigins"):
            assert raw[key] == old[key]
        assert r["geometryFingerprint"] == reference["geometryFingerprint"] and r["houseAirMask"] == reference["houseAirMask"]
        assert [s["physicalSecond"] for s in r["samples"]] == [s["physicalSecond"] for s in reference["samples"]]
        for a, b in zip(r["samples"], reference["samples"]):
            scalar_errors.extend(abs(a[key]-b[key]) for key in ("roomOffsetC", "materialOffsetC", "nearSourceOffsetC"))
        for key in ("houseFieldAt600", "houseFieldAt680", "houseFieldLateMean"):
            assert len(r[key]) == len(reference[key]) == 2000
            field_errors.extend(abs(a-b) for a,b in zip(r[key],reference[key]))
        rows.append(r)
    assert len(scalar_errors) == 90 and len(field_errors) == 18000
    assert max(scalar_errors) == max(field_errors) == 0
    result = {"writtenBy": "ChatGPT", "derivedFrom": ["dry-0.json", "dry-1.json", "dry-2.json",
              "../air-response-2026-10-20/responses.json"], "cases": 3,
              "acceptedSteps": sum(r["acceptedSteps"] for r in rows), "scalarValues": len(scalar_errors),
              "fieldValues": len(field_errors), "maxDifference": 0}
    (ROOT / "summary.json").write_text(json.dumps(result, indent=2) + "\n")
    print(json.dumps(result, indent=2))
