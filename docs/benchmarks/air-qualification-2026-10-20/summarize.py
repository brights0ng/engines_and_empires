# Written by ChatGPT.
"""Derive matched-house errors from the archived raw reports; Python standard library only."""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def load(name, expected):
    report = json.loads((ROOT / name).read_text())
    assert report.get("complete", True), f"Incomplete checkpoint: {name}"
    rows = report["results"]
    assert len(rows) == expected
    for row in rows:
        assert row["completedPhysicalSeconds"] == row["advancedPhysicalSeconds"] == 680
        assert not row["firstFailure"] and row["lateWindowComplete"]
        assert row["acceptedSteps"] * row["physicalStep"] == 680
        assert row["lateWindowSamples"] == 60 / row["physicalStep"] + 1
    return rows


def error(a, b, mask):
    temps = [abs(a[4*i] - b[4*i]) for i in range(len(mask))]
    speeds = [abs(a[4*i+j] - b[4*i+j]) for i, air in enumerate(mask) if air for j in (1, 2, 3)]
    return {"maxTemperatureC": max(temps), "rmsTemperatureC": math.sqrt(sum(x*x for x in temps)/len(temps)),
            "maxVelocity": max(speeds), "rmsVelocity": math.sqrt(sum(x*x for x in speeds)/len(speeds))}


def comparison(a, b):
    assert a["houseAirMask"] == b["houseAirMask"]
    assert [s["physicalSecond"] for s in a["samples"]] == [s["physicalSecond"] for s in b["samples"]]
    return {"opening": a["opening"], "fields": {
        label: error(a[key], b[key], a["houseAirMask"])
        for label, key in (("at600", "houseFieldAt600"), ("at680", "houseFieldAt680"), ("lateMean", "houseFieldLateMean"))},
        "samplesAminusB": [{"physicalSecond": x["physicalSecond"], **{
            key: x[key]-y[key] for key in ("roomOffsetC", "materialOffsetC", "nearSourceOffsetC")}}
            for x, y in zip(a["samples"], b["samples"])]}


if __name__ == "__main__":
    time = load("timesteps.json", 9)
    boundary = load("boundaries.json", 6)
    ground = load("ground.json", 3)
    names = ("sealed", "window", "missing-wall")
    report = {"writtenBy": "ChatGPT", "derivedFrom": ["timesteps.json", "boundaries.json", "ground.json"],
              "differenceDirection": "first configuration minus reference; field errors are absolute",
              "timestepHalfVsQuarter": [], "padding8Vs16": [], "ground6Vs3": []}
    baseline_path = ROOT.parent / "air-hotloops-2026-10-20" / "calibration.json"
    baseline = json.loads(baseline_path.read_text())["results"]
    prefix_errors = []
    for old in baseline:
        refined = next(r for r in time if r["opening"] == old["opening"] and r["physicalStep"] == 1)
        for sample in old["samples"]:
            current = next(s for s in refined["samples"] if s["physicalSecond"] == sample["physicalSecond"])
            for key, old_key in (("roomOffsetC", "coupledMeanOffsetC"), ("materialOffsetC", "coupledMeanMaterialOffsetC"),
                                 ("nearSourceOffsetC", "coupledNearSourceOffsetC")):
                prefix_errors.append(abs(current[key] - sample[old_key]))
    assert len(prefix_errors) == 81 and max(prefix_errors) == 0
    report["baselinePrefixCheck"] = {"source": "../air-hotloops-2026-10-20/calibration.json",
                                    "values": len(prefix_errors), "maxDifference": max(prefix_errors)}
    for name in names:
        report["timestepHalfVsQuarter"].append(comparison(
            next(r for r in time if r["opening"] == name and r["physicalStep"] == .5),
            next(r for r in time if r["opening"] == name and r["physicalStep"] == .25)))
        a = next(r for r in boundary if r["opening"] == name and r["horizontalPadding"] == 8)
        b = next(r for r in boundary if r["opening"] == name and r["horizontalPadding"] == 16)
        report["padding8Vs16"].append(comparison(a, b))
        report["ground6Vs3"].append(comparison(next(r for r in ground if r["opening"] == name), a))
    all_rows = time + boundary + ground
    report["quality"] = {"cases": len(all_rows), "acceptedSteps": sum(r["acceptedSteps"] for r in all_rows),
                         **{key: max(abs(r[key]) for r in all_rows) for key in (
                             "maxStepEnergyError", "maxDivergence", "totalEnergyBudgetError")}}
    (ROOT / "comparisons.json").write_text(json.dumps(report, indent=2) + "\n")
    print(json.dumps({**report["quality"], **{key: [
        {"opening": row["opening"], "at600": row["fields"]["at600"],
         "room600": next(s["roomOffsetC"] for s in row["samplesAminusB"] if s["physicalSecond"] == 600),
         "room680": next(s["roomOffsetC"] for s in row["samplesAminusB"] if s["physicalSecond"] == 680)}
        for row in report[key]] for key in ("timestepHalfVsQuarter", "padding8Vs16", "ground6Vs3")}}, indent=2))
