<!-- Written by Claude; moved by Gemini. -->
# Known issues

Problems we know about and have not fixed, with the workaround. Add a line when you find one; remove it when it is fixed.

## Meteodyne

- **`/meteodyne weather climatology run` on a dedicated server.** The run takes about 2 minutes on the server thread
  (it builds the weather field before it starts), and the dedicated server's watchdog (`max-tick-time` in
  `server.properties`, 60000 ms by default) kills the server. Run it in singleplayer, or set `max-tick-time=-1` first.
  Found in split step 3; the same code behaves the same before the split.
