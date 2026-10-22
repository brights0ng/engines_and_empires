# Written by ChatGPT.
"""Compare the missing-wall house at padding 8, 16 and 24 without changing raw data."""
import json
import math
from summarize import ROOT, comparison, load


def maximum_component(row, key):
    return max(abs(value) for i, value in enumerate(row[key]) if i % 4)


if __name__ == "__main__":
    boundary = load("boundaries.json", 6)
    larger = load("missing-wall-padding24.json", 1)
    roots = [json.loads((ROOT / name).read_text()) for name in ("boundaries.json", "missing-wall-padding24.json")]
    for key in ("flowSettings", "thermalSettings", "thermalTraversal", "cachedSamplingOrigins", "flowKernel", "sequence"):
        assert roots[0][key] == roots[1][key], f"Configuration mismatch: {key}"
    rows = [next(r for r in boundary if r["opening"] == "missing-wall" and r["horizontalPadding"] == p) for p in (8, 16)] + larger
    for row, padding in zip(rows, (8, 16, 24)):
        assert row["opening"] == "missing-wall" and row["physicalStep"] == .5
        assert row["horizontalPadding"] == row["topPadding"] == padding
        assert row["nx"] == row["nz"] == 12 + 2*padding and row["ny"] == 10 + padding
        assert row["groundDepth"] == 3 and row["responseScale"] == 1
        assert row["sourcePower"] == 75 and row["sourceLimit"] == "unlimited"
        assert len(row["houseAirMask"]) == 500
        for key in ("houseFieldAt600", "houseFieldAt680", "houseFieldLateMean"):
            assert len(row[key]) == 2000
            assert all(math.isfinite(x) for x in row[key])
    comparisons = []
    for a, b in ((rows[0], rows[1]), (rows[1], rows[2]), (rows[0], rows[2])):
        result = comparison(a, b)
        result.update({"padding": a["horizontalPadding"], "referencePadding": b["horizontalPadding"]})
        comparisons.append(result)
    report = {"writtenBy": "ChatGPT", "derivedFrom": ["boundaries.json", "missing-wall-padding24.json"],
              "differenceDirection": "smaller minus larger; field errors are absolute",
              "scope": "600 s heating plus 80 s cooling; fixed grid spacing; no infinite-domain or equilibrium claim",
              "cases": [{**{key: row[key] for key in (
                  "horizontalPadding", "nx", "ny", "nz", "acceptedSteps", "maxDivergence", "maxStepEnergyError",
                  "totalEnergyBudgetError", "lateMaxHouseVelocityRange", "lateMaxVelocitySecondDifferenceOverDtSquared")},
                         "maxHouseVelocityComponentAt600": maximum_component(row, "houseFieldAt600"),
                         "samples": row["samples"]} for row in rows], "comparisons": comparisons}
    (ROOT / "radius-comparisons.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({"cases": report["cases"], "comparisons": comparisons}, indent=2))
