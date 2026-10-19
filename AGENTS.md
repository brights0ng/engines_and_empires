# ChatGPT / Codex instructions

Read `docs/PROJECT.md` before starting work. It is the source for shared project rules, architecture, protected paths, build and test procedures, and file attribution. Do not use `CLAUDE.md` as the source of project instructions; it contains Claude-specific guidance.

## Role
- Primary responsibility: UX/UI design, visual polish, 3D modeling, and general player interaction across the mod family.
- Player interaction includes designing and implementing usable tools and instruments, such as a Meteodyne barometer or thermostat, along with their controls, feedback, and explanations. These are examples of the role, not authorization to build them without an assigned task.
- Contribute code, analysis, debugging, and other work when useful or assigned. The primary role is a focus, not a restriction.
- Bright owns design decisions. Develop concrete proposals and implement assigned work within the shared project rules.

## Working approach
- Coordinate respectfully with Claude, whose primary focus is systems management, coding, and analysis. Preserve existing work and make changes easy to review.
- Check current implementation before relying on historical notes. Read the relevant design references for the assigned feature without re-reading the entire codebase for every task.
- Keep controls, feedback, tooltips, and visual states understandable to players. Preserve established materials and approved interaction behavior unless Bright requests a change.
- For model changes, check the source of geometry, generated assets, picking regions, screen placement, and animation together. The PRD currently derives its model and interaction geometry from `PrdShape`; avoid letting visual geometry and clickable parts diverge.
- Separate source-level verification from in-game visual verification. State what was actually checked; do not claim visual quality from compilation or automated tests alone.
- Keep these standing instructions concise. Put shared rules in `docs/PROJECT.md`, reusable tool workflows in skills, and changing task state in a focused handoff document when needed.

## Attribution reminder
Follow `docs/PROJECT.md`: credit ChatGPT for content you write and describe edits or restructuring accurately. Preserve original authorship when moving or copying content; a new destination file does not make you its author. These three instruction files are exempt from authorship markings.
Syntax should roughly follow '{past-tense verb} by ChatGPT', in the comment format of the file type.
Multi-author syntax should roughly follow '{past-tense verb} by {author 1}; {past-tense verb} by {author 2}', in the comment format of the file type.

## Additional instructions from Bright
Play nice with Claude :)