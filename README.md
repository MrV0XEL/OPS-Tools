# OPS-Tools

A [Meteor Client](https://meteorclient.com) addon with PvP, building, chat and stash tools. Everything shows up in
the **OPS Tools** category of the Meteor GUI.

> **Use responsibly.** Many of these modules automate PvP or building and can break the rules of some servers or be
> flagged by anti-cheats. Check a server's rules before using them there.

## Supported versions

One jar per Minecraft version: **1.21.4, 1.21.11, 26.1, 26.2, 26.3**. Use the jar that matches your game and the Meteor
Client build for the same version.

## Installation

1. Install Fabric Loader for your Minecraft version.
2. Put the Meteor Client jar for that version in `mods/`.
3. Put the matching `ops-tools-<version>.jar` in `mods/`.
4. Start the game, open the Meteor GUI (default `Right Shift`) and find the **OPS Tools** tab.

Optional: [Baritone](https://github.com/cabaletta/baritone) (Meteor's build) for modules that can path to targets,
and [Litematica](https://modrinth.com/mod/litematica) for the Printer.

## Building from source

See `BUILD.md` in each project zip. In short: add `gradlew`, `gradlew.bat` and `gradle/wrapper/gradle-wrapper.jar`
from the Meteor addon template, then run `./gradlew build`. The jar is in `build/libs/`.

## Modules

Open a module's settings in the GUI for every option. Names below are as they appear in the GUI.

### PvP

| Module | What it does | How to use |
| --- | --- | --- |
| **crystal-aura-plus** | Places and breaks end crystals on enemies. Picks the position with the most damage to them while staying inside your self-damage and friend-damage limits. | Hold crystals in the hotbar, enable it near an enemy. Tune the *Damage* group (min damage, max self damage). |
| **anchor-aura-plus** | Places, charges and explodes respawn anchors on enemies (Overworld and End only; refuses to run in the Nether). | Needs respawn anchors **and** glowstone in the hotbar. Keep glowstone out of your offhand. |
| **auto-trap-plus** | Walls the nearest enemy in with obsidian (modes: Top, Eyes, Both, Full). Places support blocks when needed. | Put obsidian in the hotbar. Set `mode`, `target-range`, `places-per-tick`. |
| **self-trap-plus** | Walls *you* in (head ring and/or top) so crystals, pistons and anvils can't reach you. Turns itself off when you move, change height or sneak. | Put obsidian in the hotbar and enable it. |
| **hole-snap** | Walks you into the nearest safe hole (bedrock/obsidian walls and floor). Disables itself when you arrive, get stuck, get rubberbanded or time out. | Enable it near a hole. Adjust `range` and `speed`. |
| **totem-pop-tracker** | Counts and reports every totem pop and death of nearby players in chat. | Enable it. Set `range` and whether to track yourself. |
| **kill-effects** | Client-side effect where something dies: lightning, explosion, firework, flames, hearts, soul, portal, totem, notes, angry, smoke, enchant, stars, snow, tornado or random. | Pick `effect` and which `entities` trigger it. Visual only, on your screen. |

### Building and farming

| Module | What it does | How to use |
| --- | --- | --- |
| **printer** | Prints a loaded Litematica schematic around you using fast packet placement, with ghost-block checks and an adaptive rate. | Load a schematic in Litematica, stand near it, enable the module. Turn *air place* off if the server's anti-cheat flags it. |
| **Map Placer** | Fills item frames with maps. *Single* mode fills one frame; *Wall* mode builds a wall and fills a numbered WxH map art. | Hold the maps, pick the mode in settings, right-click to start (see the module's settings). |
| **advanced-auto-wither** | Builds withers one after another in front of you, with retries, ghost-block checks and an automatic name tag. | Needs soul sand/soil and wither skulls in your hotbar. Aim at the spot and right-click to start (and to stop). Set `start-trigger` to *Keybind* and pick `start-key` to use any other key or mouse button instead, so right-click keeps working normally. |
| **ender-chest-farmer** | Farms obsidian from ender chests with a burst instant rebreak. Chests are placed from the offhand, the pickaxe stays in the main hand. | Put ender chests in the offhand and a pickaxe without silk touch in your main hand. The first chest is mined normally; then the burst takes over. |
| **auto-sign-plus** | Places a sign every 5-20 blocks (random gap, configurable) as you travel and writes your text on it, on **both sides**. Optionally dyes it, makes it glow and waxes it using items in your inventory. Floor placement is the default (it looks straight down server-side so you get standing signs, not wall signs); *wall-place* prefers walls and *air-place* clicks the target block itself. | Carry signs (and optionally dye, glow ink sacs, honeycomb). Type your lines in the *Text* group (`{x} {y} {z}` and `{player}` work); the back can use the same lines or its own. Don't sneak while it runs. |
| **container-cleaner** | Throws unwanted items out of an open container (chest, barrel, shulker, hopper, dispenser). *Blacklist* throws the listed items; *Whitelist* throws everything else. | Open a container with the module on. Set `mode` and `items`. |

### Chat and interface

| Module | What it does | How to use |
| --- | --- | --- |
| **chat-sentinel** | Mute rules, highlight rules and keyword alerts (sound and toast), plus a movable, resizable chat with no black background. Every rule is a regex or a plain word. | With chat open: drag with left mouse to move, Shift+drag to resize, right-click to reset. If `require-drag-key` is on, hold Left Alt. |
| **gui-style** | Re-skins the Meteor GUI: colour presets (Holographic, Arctic, Deep Ocean, AquaGlass, Crimson, Violet, Midnight, Emerald, Sunset, Mono, Custom), window transparency, rainbow/pulse accent, tint, gradient, vignette and edge glow behind the GUI, background blur, GUI scale and font. Smooth animations (fade in/out, blur ramp, colours that glide, breathing glow) and clicking sounds as you move over and press modules. Original colours come back when you turn it off. | Enable it and pick a `theme`. Use *Custom Colors* for your own, *Animations* and *Sounds* to tune the feel. If a colour doesn't change, toggle `dump-theme` once and send the printed list. |
| **gui-particles** | Animated snow, rain, stars, bubbles, fireflies, embers or confetti behind the Meteor ClickGUI and HUD editor. | Enable it and choose a particle type. |

### Stash tools

| Module | What it does |
| --- | --- |
| **Global Stash Tracker** | Remembers what is inside every container you open (including shulker boxes) and lets you search it from anywhere with `.find`. Draws labels above containers and highlights search results. |

## Stash tracker quick start

1. Enable **Global Stash Tracker**.
2. Stand inside your stash and run `.stash create <name>`.
3. Open containers normally; each one is indexed after the server fills the menu.
4. From anywhere, run `.find <item>`. Fuzzy matching handles typos, partial words and item ids.

### Commands

| Command | What it does |
| --- | --- |
| `.find <item>` | Fuzzy search across all indexed stashes. Prints the best 10 matches and highlights matching containers in your current dimension for 30 seconds. |
| `.stash create <name>` | Register a stash centered on where you stand. |
| `.stash delete <name>` | Delete a stash and everything indexed in it. Run it twice within 15 seconds to confirm. |
| `.stash rename <old> <new>` | Rename a stash (use "quotes" for an old name with spaces). |
| `.stash move <name>` | Move a stash's center to where you stand. |
| `.stash clear <name>` | Forget the indexed containers but keep the stash. |
| `.stash info <name>` | Show a stash's size and a summary of its contents. |
| `.stash list` | List all stashes. |
| `.stash near` | Show the nearest stash in this dimension. |

### Stash tracker settings

| Setting | Default | Meaning |
| --- | --- | --- |
| `assign-radius` | 64 | A container opened within this many blocks of a stash is filed under it. |
| `prompt-unregistered` | on | Occasionally tells you when you open a container outside every stash. |
| `fuzzy-threshold` | 0.6 | Minimum match score for `.find` (lower = more forgiving). |
| `render-labels` | on | Floating labels above tracked containers and stash centers. |
| `label-range` / `max-labels` / `label-scale` | 32 / 12 / 0.035 | How far, how many and how big labels are. |
| `search-highlight-fill` / `-outline` | amber | Colors of the `.find` highlight. |

Notes: only containers you open are indexed (ender chests are ignored); menus from plugins or chest minecarts are
ignored on purpose; the index is only as fresh as the last time you opened each container. Data is saved in
`ops-tools/stashes.json` inside the Meteor folder, with a `.bak` backup per session.

## Tips

- Most modules show their warnings in chat (for example "you need obsidian in your hotbar"). Read them first when
  something does nothing.
- Lower the `delay` / `places-per-tick` settings if a server kicks you or refuses placements; raise them on high ping.
- Settings are saved by Meteor, so you can use Meteor's profiles to keep different setups.

## Credits and license

Built on the [Meteor Client addon API](https://github.com/MeteorDevelopment/meteor-addon-template). Add your own
license here.
