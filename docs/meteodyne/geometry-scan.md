Written by Claude.

# Geometry scanner

The scanner captures world geometry and low-rate play recordings for GreedyFluids' 3D design. It reads loaded chunks without loading missing chunks or changing blocks.

```
/meteodyne scan snapshot [label] [rxz] [ry]
/meteodyne scan record start
/meteodyne scan record stop
/meteodyne scan record status
/meteodyne scan record mark <greedy text>
/meteodyne scan record auto on
/meteodyne scan record auto off
```

`/gfscan` aliases `/meteodyne scan`: for example, `/gfscan snapshot room 64 32` or `/gfscan record start`. The Meteodyne root requires operator level 2. The alias also permits the owner of a singleplayer world with cheats disabled.

Snapshot arguments are positional: label defaults to `snap`, horizontal radius `rxz` defaults to 64 (8–128), and vertical radius `ry` defaults to 32 (8–64). Labels use word characters: letters, digits, underscores, hyphens, periods and plus signs. The box includes the centre minus each radius through the centre plus each radius minus one. Replies include the path, compressed size, cell count and capture time. Filenames have second resolution; a second capture with the same name in that second is rejected to preserve the first file.

Files go in `greedyfluids-scans` under `server.getServerDirectory()`, which is the game folder in singleplayer. Snapshots are `snap-yyyyMMdd-HHmmss-label.gfsnap`; recordings are `rec-yyyyMMdd-HHmmss.gfrec`.

Only one recording can run at a time. Start requires a player; the anchor follows that player, including dimension changes. Block edits are recorded within 48 blocks in each axis, positions every 10 ticks, and load/weather every 20 ticks. Section summaries cover a radius of 3 sections, including vertical sections inside build height. Up to 16 summaries are attempted each tick, nearest entries first; missing chunks remain queued. A summary describes a section when it enters the range, and block records describe subsequent edits. Stop writes END, drains the writer and closes the gzip file. Logging out of the recorded player or stopping the server also ends the recording.

Status reports duration in ticks converted to seconds at 20 ticks per second, per-tag record counts, compressed bytes written so far, off-thread drops and pending sections. Buffered data is handed to a daemon writer every 200 ticks or at 1 MiB, so the size while recording can lag behind captured records. `mark` adds free text (up to 21,000 characters).

Auto mode creates or deletes the `greedyfluids-scans/autorecord` marker. When the marker exists, the next player login starts a recording if none is active. Turning auto off affects future logins; stop the current recording separately. The marker persists across restarts.

Classification uses the first matching rule: AIR for the heat table's AIR cell; LAVA for lava fluid; WATER for any other nonempty fluid (including waterlogged states); OPENING for doors, trapdoors and fence gates; POROUS for leaves; PARTIAL for a nonempty collision shape with open heat-table faces; SOLID otherwise. UNKNOWN is reserved for the two snapshot sentinels. State flag bit 0 denotes a powered heat source and bit 1 denotes an air-source candidate.

## Format

```text
FORMAT (version 1; gzip of big-endian DataOutputStream; writeUTF strings; ticks are level.getGameTime()):
Snapshot .gfsnap:
  int magic 0x4746534E; int version 1; UTF label; UTF dimension id; UTF biome id at the player (or "#none");
  long gameTime; long dayTime; boolean raining; boolean thundering;
  double px, py, pz (player feet, or the centre when there is no player); float yaw (0 with no player);
  int x0, y0, z0; int nx, ny, nz;
  int paletteSize (<65536); paletteSize × { UTF state; byte class; byte flags }   (entry 0 "#unloaded", entry 1 "#outside", both class 7, flags 0)
  nx·ny·nz × short palette index, cell (x,y,z) at (y·nz + z)·nx + x
  nx·nz × int MOTION_BLOCKING first-free y, index z·nx + x (Integer.MIN_VALUE when unloaded)
  nx·nz × int WORLD_SURFACE, same convention
Recording .gfrec:
  header: int magic 0x47465243; int version 1; long startGameTime; long startEpochMillis; int blockRadius 48; int sectionRadius 3; int trajectoryInterval 10
  records, each byte tag + payload, nondecreasing tick:
    0 END      long tick; int droppedOffThread; int sectionsPending
    1 STATE    int id; UTF state; byte class; byte flags            (ids from 0)
    2 DIM      long tick; UTF dimension                             (at start and on change)
    3 POS      long tick; float x, y, z; byte flags                 (bit0 passenger, bit1 Ships.get().near(level, x, y, z, 8), bit2 isFallFlying, bit3 getAbilities().flying)
    4 BLOCK    long tick; int x, y, z; int beforeId; int afterId
    5 ACTION   long tick; int x, y, z; byte kind                    (0 break, 1 place, 2 use)
    6 SECTION  long tick; int sx, sy, sz; 8 × short counts (class ids 0–7, sum 4096); short sourceCount; int uniformId (−1 if mixed)
    7 LOAD     long tick; float msptAvg; byte weather               (bit0 raining, bit1 thundering)
    8 MARK     long tick; UTF text
```
