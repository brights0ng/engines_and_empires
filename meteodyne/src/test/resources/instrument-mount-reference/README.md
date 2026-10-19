<!-- Reference pixel art created by Bright; test fixture organization and analysis written by ChatGPT. -->
# Bright's mount texture references

These seven original PNGs were supplied by Bright in the generated block-texture folder on 2026-10-19. They are preserved here before generation replaces that folder's working copies. They are **test references only**: production code and data generation never load or copy them.

`InstrumentMetalTexturesTest` checks generated logical panels against these originals pixel for pixel. Generated sprites are padded to 16×16; model UVs select only the logical panel and rotate portrait faces, preserving one texel per model pixel without reducing the block atlas's mipmap resolution.

| Reference | Logical size | Bolts (top-left pixels, zero-based) |
| --- | --- | --- |
| `instrument_base_side.png` | 16×2 | none |
| `instrument_support_front.png` | 14×8 | (2,3), (10,3) |
| `instrument_support_side.png` | 8×4 | none |
| `instrument_support_top.png` | 14×4 | none |
| `temperature_sensor_back.png` | 13×6 | (2,2), (9,2) |
| `temperature_sensor_side.png` | 13×2 | none |
| `temperature_sensor_top.png` | 6×2 | none |

Bolts are 2×2. A full 16×16 tile puts them at (2,2), (12,2), (2,12), (12,12). Keep the one-pixel outer edge and highlight rim instead of rescaling them. Keep a two-pixel edge inset for bolts where possible; if two edge bolts would touch, replace them with one centered bolt. Below six pixels, the dimension cannot fit both margins and a bolt, so omit bolts. Thin two-pixel strips retain only the edge color; four-pixel strips retain the edge and highlight rim. The compact 13×6 back uses the core field color between its bolts and has the right bolt's highlight at its lower-left pixel, matching Bright's reference.
