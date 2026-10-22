# Written by ChatGPT.
"""Validate finite-horizon response runs and their unchanged unity controls."""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def at(row, second):
    return next(s for s in row["samples"] if s["physicalSecond"] == second)


def error(a, b, mask):
    temps = [abs(a[4*i]-b[4*i]) for i in range(len(mask))]
    velocity = [abs(a[4*i+j]-b[4*i+j]) for i, air in enumerate(mask) if air for j in (1,2,3)]
    return {"maxTemperatureC": max(temps), "rmsTemperatureC": math.sqrt(sum(x*x for x in temps)/len(temps)),
            "maxVelocityComponent": max(velocity), "rmsVelocityComponent": math.sqrt(sum(x*x for x in velocity)/len(velocity))}


if __name__ == "__main__":
    raw = json.loads((ROOT / "responses.json").read_text())
    quarter = json.loads((ROOT / "responses-quarter.json").read_text())
    assert raw["complete"] and quarter["complete"]
    assert len(raw["results"]) == len(quarter["results"]) == 15
    rows = raw["results"]
    all_rows = rows + quarter["results"]
    assert {(r["opening"], r["responseScale"], r["physicalStep"]) for r in all_rows} == {
        (name, scale, dt) for name in ("sealed", "window", "missing-wall") for scale in (.1, .5, 1, 2, 10) for dt in (.5,.25)}
    for r in all_rows:
        assert not r["firstFailure"], r["firstFailure"]
        assert r["acceptedSteps"] == 680/r["physicalStep"] and r["advancedPhysicalSeconds"] == r["completedPhysicalSeconds"] == 680
        assert r["horizontalPadding"] == r["topPadding"] == 6 and r["groundDepth"] == 3
        assert r["sourcePower"] == 75 and r["sourceLimit"] == "unlimited" and len(r["houseAirMask"]) == 500
        assert r["maxDivergence"] <= 1e-7 and r["maxStepEnergyError"] <= 2e-8
        assert r["advancedThermalSeconds"] == 680 * r["responseScale"]
        assert math.isclose(r["deliveredSourceEnergy"], 45000*r["responseScale"], abs_tol=1e-6)
        assert r["lateWindowComplete"] and r["lateWindowSamples"] == 60/r["physicalStep"]+1
        for key in ("houseFieldAt600", "houseFieldAt680", "houseFieldLateMean"):
            assert len(r[key]) == 2000 and all(math.isfinite(x) for x in r[key])
    previous = json.loads((ROOT.parent / "air-qualification-2026-10-20" / "timesteps.json").read_text())
    for key in ("flowSettings", "thermalSettings", "thermalTraversal", "flowKernel", "cachedSamplingOrigins"):
        assert raw[key] == quarter[key] == previous[key]
    prefix = []
    field_errors = []
    for r in all_rows:
        if r["responseScale"] != 1:
            continue
        old = next(p for p in previous["results"] if p["opening"] == r["opening"] and p["physicalStep"] == r["physicalStep"])
        for s in r["samples"]:
            old_sample = at(old, s["physicalSecond"])
            prefix.extend(abs(s[key]-old_sample[key]) for key in ("roomOffsetC", "materialOffsetC", "nearSourceOffsetC"))
        for key in ("houseFieldAt600", "houseFieldAt680", "houseFieldLateMean"):
            field_errors.extend(abs(a-b) for a,b in zip(r[key],old[key]))
    assert len(prefix) == 180 and max(prefix) == max(field_errors) == 0
    refinement = []
    for r in rows:
        q = next(p for p in quarter["results"] if p["opening"] == r["opening"] and p["responseScale"] == r["responseScale"])
        assert r["houseAirMask"] == q["houseAirMask"] and r["geometryFingerprint"] == q["geometryFingerprint"]
        refinement.append({"opening": r["opening"], "responseScale": r["responseScale"],
                           "roomAt600HalfMinusQuarter": at(r,600)["roomOffsetC"]-at(q,600)["roomOffsetC"],
                           "roomAt680HalfMinusQuarter": at(r,680)["roomOffsetC"]-at(q,680)["roomOffsetC"],
                           "fields": {label: error(r[key],q[key],r["houseAirMask"]) for label,key in (
                               ("at600","houseFieldAt600"),("at680","houseFieldAt680"),("lateMean","houseFieldLateMean"))}})
    by_scale = []
    for scale in (.1,.5,1,2,10):
        cases = [r for r in refinement if r["responseScale"] == scale]
        by_scale.append({"responseScale": scale,
                         "maxRoomDifferenceAt600C": max(abs(r["roomAt600HalfMinusQuarter"]) for r in cases),
                         "maxRoomDifferenceAt680C": max(abs(r["roomAt680HalfMinusQuarter"]) for r in cases),
                         "maxHouseTemperatureDifferenceAt600C": max(r["fields"]["at600"]["maxTemperatureC"] for r in cases),
                         "maxHouseVelocityComponentDifferenceAt600": max(r["fields"]["at600"]["maxVelocityComponent"] for r in cases),
                         "maxLateMeanVelocityComponentDifference": max(r["fields"]["lateMean"]["maxVelocityComponent"] for r in cases)})
    summary = {"writtenBy": "ChatGPT", "derivedFrom": ["responses.json", "responses-quarter.json", "../air-qualification-2026-10-20/timesteps.json"],
               "scope": "600 s heating plus 80 s cooling, 0.5/0.25 s physical steps, original house domain; no equilibrium claim",
               "unityControl": {"scalarValues": len(prefix), "fieldValues": len(field_errors), "maxDifference": 0},
               "quality": {"cases": len(all_rows), "acceptedSteps": sum(r["acceptedSteps"] for r in all_rows), **{
                   key: max(abs(r[key]) for r in all_rows) for key in ("maxDivergence", "maxStepEnergyError", "totalEnergyBudgetError")}},
               "refinement": refinement,
               "refinementByScale": by_scale,
               "cases": [{"opening": r["opening"], "responseScale": r["responseScale"],
                          "roomAt60": at(r,60)["roomOffsetC"], "roomAt600": at(r,600)["roomOffsetC"],
                          "roomAfter40Cooling": at(r,640)["roomOffsetC"],
                          "fractionAfter40Cooling": at(r,640)["roomOffsetC"]/at(r,600)["roomOffsetC"],
                          "materialAt600": at(r,600)["materialOffsetC"],
                          "lateVelocityRange": r["lateMaxHouseVelocityRange"],
                          "lateVelocitySecondDifferenceOverDtSquared": r["lateMaxVelocitySecondDifferenceOverDtSquared"]} for r in rows]}
    (ROOT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
