# Written by ChatGPT.
"""Validate half-second settled calibration and compare with the archived one-second run."""
import json
import math
from pathlib import Path

ROOT = Path(__file__).resolve().parent


def samples_by_time(row, key):
    return {s["physicalSecond"]: s for s in row[key]}


if __name__ == "__main__":
    raw = json.loads((ROOT / "equilibrium-half.json").read_text())
    assert raw["complete"] and raw["equilibriumPhysicalStep"] == .5
    rows = raw["results"]
    assert len(rows) == 3 and {r["opening"] for r in rows} == {"sealed", "window", "missing-wall"}
    old = json.loads((ROOT.parent / "air-equilibrium-2026-10-20" / "equilibrium-long.json").read_text())
    short = json.loads((ROOT.parent / "air-response-2026-10-20" / "responses.json").read_text())
    for key in ("flowSettings", "thermalSettings", "thermalTraversal", "flowKernel", "cachedSamplingOrigins"):
        assert raw[key] == old[key] == short[key]
    legacy_errors, coupled_errors, comparisons = [], [], []
    for row in rows:
        assert row["equilibriumQualified"] and not row["firstFailure"]
        assert row["physicalStep"] == .5 and row["legacyPhysicalStep"] == row["responseScale"] == 1
        assert row["completedCoolingSeconds"] == 900 and row["completedHeatingSeconds"] <= row["maxHeatSeconds"]
        assert row["acceptedSteps"] * .5 == row["advancedPhysicalSeconds"] == row["completedHeatingSeconds"] + 900
        assert math.isclose(row["deliveredSourceEnergy"], 75*row["completedHeatingSeconds"], abs_tol=1e-6)
        assert row["maxDivergence"] <= 1e-7 and row["maxStepEnergyError"] <= 2e-8
        assert row["windowSeconds"] == 1000 and row["requiredConsecutiveWindows"] == 3
        assert row["temperatureChangeThresholdC"] == row["storageRateThreshold"] == .001
        assert row["faceFluxChangeThreshold"] == 1e-5 and row["roomWindowRangeThresholdC"] == .01
        windows = row["settlingWindows"]
        assert len(windows) == row["completedHeatingSeconds"] / 1000
        assert all(w["coupledSamples"] == 2000 for w in windows)
        final = windows[-3:]
        for w in final:
            assert w["maxCoupledWindowMeanChangeC"] <= .001 and w["maxLegacyWindowMeanChangeC"] <= .001
            assert abs(w["meanStorageRate"]) <= .001 and w["roomMeanRangeC"] <= .01
        if row["strictInstantaneousSteadyQualified"]:
            for w in final:
                assert w["maxCoupledStateChangeC"] <= .001 and w["maxLegacyCellChangeC"] <= .001
                assert w["maxLocalTemperatureRangeC"] <= .001 and w["maxLegacyCellRangeC"] <= .001
                assert w["maxFaceFluxChange"] <= 1e-5 and w["maxFaceFluxRange"] <= 1e-5
            assert windows[-1]["consecutiveStrictStableWindows"] >= 3
        historical = next(r for r in old["results"] if r["opening"] == row["opening"])
        previous = samples_by_time(historical, "heatingSamples")
        current = samples_by_time(row, "heatingSamples")
        for second in previous.keys() & current.keys():
            legacy_errors.extend(abs(previous[second][key]-current[second][key]) for key in (
                "legacyMeanOffsetC", "legacyMeanMaterialOffsetC", "legacyNearSourceOffsetC"))
        prefix = next(r for r in short["results"] if r["opening"] == row["opening"] and r["responseScale"] == 1)
        for s in prefix["samples"]:
            second = s["physicalSecond"]
            if second > 600:
                continue  # That archive turns its source off at 600 s.
            coupled_errors.extend(abs(s[a]-current[second][b]) for a,b in (
                ("roomOffsetC", "coupledMeanOffsetC"), ("materialOffsetC", "coupledMeanMaterialOffsetC"),
                ("nearSourceOffsetC", "coupledNearSourceOffsetC")))
        cooling = samples_by_time(row, "coolingSamples")
        assert math.isclose(row["coupledFractionRemainingAt40Seconds"],
                            cooling[40]["coupledMeanOffsetC"]/row["coupledCoolingStartRoomOffsetC"], abs_tol=1e-14)
        comparisons.append({"opening": row["opening"], "heatingSeconds": row["completedHeatingSeconds"],
                            "legacyMeanRiseC": row["legacyEquilibriumMeanOffsetC"],
                            "coupledOneSecondMeanRiseC": historical["coupledEquilibriumMeanOffsetC"],
                            "coupledHalfSecondMeanRiseC": row["coupledEquilibriumMeanOffsetC"],
                            "halfMinusLegacyMeanRiseC": row["coupledEquilibriumMeanOffsetC"]-row["legacyEquilibriumMeanOffsetC"],
                            "halfMinusOneSecondMeanRiseC": row["coupledEquilibriumMeanOffsetC"]-historical["coupledEquilibriumMeanOffsetC"],
                            **{key: row[key] for key in (
                                "legacySecondsTo95PercentOwnEquilibrium", "coupledSecondsTo95PercentOwnEquilibrium",
                                "coupledSecondsTo95PercentLegacyEquilibrium", "legacyCoolingHalfSeconds", "coupledCoolingHalfSeconds",
                                "legacyCoolingTo10PercentSeconds", "coupledCoolingTo10PercentSeconds",
                                "legacyFractionRemainingAt40Seconds", "coupledFractionRemainingAt40Seconds",
                                "legacyFinalMaterialOffsetC", "coupledFinalMaterialOffsetC", "strictInstantaneousSteadyQualified")},
                            "qualifyingMaxLocalTemperatureRangeC": max(w["maxLocalTemperatureRangeC"] for w in final),
                            "qualifyingMaxFaceFluxRange": max(w["maxFaceFluxRange"] for w in final),
                            "qualifyingMaxRoomMeanRangeC": max(w["roomMeanRangeC"] for w in final)})
    assert len(coupled_errors) == 72 and max(coupled_errors) == max(legacy_errors) == 0
    summary = {"writtenBy": "ChatGPT", "derivedFrom": ["equilibrium-half.json",
               "../air-equilibrium-2026-10-20/equilibrium-long.json", "../air-response-2026-10-20/responses.json"],
               "prefixValidation": {"legacyValues": len(legacy_errors), "coupledValues": len(coupled_errors), "maxDifference": 0},
               "quality": {"acceptedSteps": sum(r["acceptedSteps"] for r in rows), **{
                   key: max(abs(r[key]) for r in rows) for key in ("maxDivergence", "maxStepEnergyError", "totalEnergyBudgetError")}},
               "cases": comparisons}
    (ROOT / "summary.json").write_text(json.dumps(summary, indent=2) + "\n")
    print(json.dumps(summary, indent=2))
