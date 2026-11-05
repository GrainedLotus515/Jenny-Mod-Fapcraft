# In-game test checklist (NeoForge 1.21.1 port)

Walk through top to bottom and tick items as you go. Report failures by item number (e.g. `9.4`).

Each item has the steps first, then `→` and the expected result. "(unverified)" means I could not confirm the expected result from the code, so tell me whatever actually happens. "(known bug?)" means the code looks wrong and I expect a failure. Please confirm it either way.

---

## Setup

**Launch**
```
nix develop
cd neoforge && ./gradlew runClient
```

**World**
- Singleplayer → Create New World → Game Mode **Creative**, Allow Commands **ON**, world type Default.
- Worldgen (houses, lairs, kobold camps) is skipped on Superflat worlds.
- The mod's items are in the **"Fapcraft"** creative tab (Allies Lamp icon). Spawn eggs are also in the vanilla Spawn Eggs tab.

**Payment**
- Creative skips every payment.
- To test costs, run `/gamemode survival` and `/give` yourself the items.

**Useful commands**
- `/gamemode creative` / `/gamemode survival`
- `/time set day` (Kobold tribes only work while the day time is under 12000. Re-run this before tribe tests.)
- `/kill @e[type=sexmod:jenny]` (swap in any entity id). `/kill @e[type=!player]` clears everything.
- `/give @s sexmod:<id>`
- Horny potion: `/give @s minecraft:potion[potion_contents={potion:"sexmod:horny_potion"}]`
- Splash horny potion: `/give @s minecraft:splash_potion[potion_contents={potion:"sexmod:horny_potion"}]`
- `/summon sexmod:<entity> ~ ~ ~`
- Place a bed nearby. Several scenes walk the girl to the nearest bed (within about 22 blocks, with a free side).

**Scene controls**
- **Sneak** = next or faster stage.
- **Space** when the horny meter (HUD bar) is full = finish.
- **Right Shift** = "Leave sex scene" (skip to the finish, or force exit).

**Mod keybinds** (category "Sex mod" in Controls)
- **G** = "Interact with your goblin"
- **Numpad 5** = "open character customisation menu"
- **Right Shift** = "Leave sex scene"

**How to report**

Send the item number, what you did, what happened, and the log `neoforge/run/logs/latest.log` (or the crash report in `neoforge/run/crash-reports/`). One line per failure is fine, e.g. `5.3 menu opened but Anal did nothing, log attached`.

---

## 0. Quick smoke test (about 10 minutes)

- [ ] 0.1 Launch, create the world. → No crash. "Fapcraft" tab lists 7 items + 9 spawn eggs.
- [ ] 0.2 Use the Jenny spawn egg, right-click her. → She renders and animates (idle, walk, blinking). Her menu opens.
- [ ] 0.3 Jenny menu → Blowjob, wait for it to finish (Space when the meter is full). → Scene plays to the end, she resets, you can move.
- [ ] 0.4 Start another Jenny scene and press R-Shift halfway through. → Clean exit. Chat works, the screen isn't dimmed.
- [ ] 0.5 Jenny menu → Equipment, put a helmet in. → Item moves in and the helmet renders on her.
- [ ] 0.6 Drink a horny potion, wait about 5 s, pick Jenny with `pick`. → You become Jenny. Drink again and pick the last entry (your own player). → Back to normal.
- [ ] 0.7 `/give @s sexmod:allies_lamp`, right-click it. → Allie appears, the wish menu opens. "Make me rich!" drops diamonds, emeralds and gold. Allie leaves and the HUD comes back.
- [ ] 0.8 Use a tribe egg on the ground. → 4 kobolds spawn. Right-click one with `sexmod:dragon_staff`. → "Name Tribe" screen opens.
- [ ] 0.9 Save & Quit, reload the world. → Girls still there with the same names. No errors in the log.

---

## 1. Highest value: never tested yet

These are the open items from PORT_NOTES, plus code paths no one has run.

- [ ] 1.1 Shift-click in Jenny's Equipment menu, both directions (armour from your inventory into her, and back out). → Items land in the right slot type and nothing is lost or duplicated.
- [ ] 1.2 Close the Equipment menu with an item still on the cursor. → The item ends up in your inventory. No dupe, no loss.
- [ ] 1.3 Shift-click in a kobold inventory (staff + right-click your own tribe kobold; see 13.8). → Works both ways.
- [ ] 1.4 Jenny Doggy at a bed: hold Sneak at the fast stage vs. not. → Hard and soft variants both play (variant is picked at the `doggyfastReady` frame).
- [ ] 1.5 Ellie "Face fuck", let the slow stage loop several times. → Slow animation changes between loops (4 variants). No T-pose or freeze.
- [ ] 1.6 Bia → Talk → doggy at a bed, reach the hard stage several times. → Hard animation varies (3 variants).
- [ ] 1.7 Ellie Cowgirl and Missionary (needs a splash potion; see 4.12). → Both reachable and play to the end.
- [ ] 1.8 Player-form solo actions: as Jenny, Ellie, Luna and Bia, sneak + right-click a bed (see 14.6-14.9). → Each starts its bed action.
- [ ] 1.9 (Optional, needs a 2nd player) Player-form scene request/accept (see 19.3). → Scene plays between the two players.
- [ ] 1.10 Allies Lamp → any wish → let Allie finish → press R-Shift. → Client not dimmed, chat opens. (This failed before the GIRL_ID fix; re-check it.)
- [ ] 1.11 Watch a kobold for a few minutes, and a Galath and a Manglelie through a scene. → Kobold eyes stay put; Galath's hair and Manglelie's skirt don't creep further each frame. (Fixed: offsets used to accumulate.)
- [ ] 1.12 Play several scenes, especially Goblin (most affected), Jenny, Slime, Ellie, Galath, Luna payment. → No limbs snapping to wild angles for a frame or two. (Fixed: constant-expression rotations were applied in radians.)
- [ ] 1.13 In a girl menu, press Tab a few times, then Enter. → The highlight moves between buttons and Enter presses the highlighted one.
- [ ] 1.14 Any first-person scene (Ellie "Face fuck" shows it best; also Jenny, Bia, Luna, Kobold, Galath). → The camera stays attached to the scene view and moves smoothly with it. No snapping back and forth every tick, and the girl's motion looks fluid. (Fixed: the camera interpolated from the wrong position field.)
- [ ] 1.15 As a girl form (potion), start a solo bed action in first person (14.6-14.9). → Same: steady camera. (Same fix, not tested in game.)
- [ ] 1.16 Pick up a goblin in first person and watch her face during the pick-up animation. → Both eyes and her mouth are visible the whole time. (Fixed: depth writes were off in this render pass.)
- [ ] 1.17 Other first-person passes drawn the same way: goblin on the shoulder (look right/down), goblin throw (12.3), Galath's energy-ball dragons (9.1), your own body as a girl form in first person. → No parts showing through other parts. (Same fix, only the goblin pick-up was checked.)

---

## 2. Spawn eggs and /summon

Use each egg with `/give @s sexmod:<id>_spawn_egg`. All 9 were checked to spawn on the dev server; this pass is about how they look and behave. Expected for each: the girl renders with idle, walk and eye animations, and there's no log spam.

- [ ] 2.1 `jenny_spawn_egg` → Jenny.
- [ ] 2.2 `ellie_spawn_egg` → Ellie.
- [ ] 2.3 `slime_spawn_egg` → Slime girl.
- [ ] 2.4 `bia_spawn_egg` → Bia.
- [ ] 2.5 `bee_spawn_egg` → Bee (flies, stays under about 3 blocks above ground).
- [ ] 2.6 `luna_spawn_egg` → Luna.
- [ ] 2.7 `goblin_spawn_egg` → Goblin.
- [ ] 2.8 `galath_spawn_egg` → Galath (wild: boss bar, hostile to survival players).
- [ ] 2.9 `manglelie_spawn_egg` → Manglelie appears and stays. Wild ones run from players within 20 blocks.
- [ ] 2.10 Confirm that neither `sexmod:allie_spawn_egg` nor `sexmod:kobold_spawn_egg` exists. → `/give` says unknown item.
- [ ] 2.11 `/summon` each of: `jenny`, `ellie`, `slime`, `bia`, `bee`, `luna`, `goblin`, `galath`, `kobold` (all `sexmod:`). → Each spawns and stays.
- [ ] 2.12 `/summon sexmod:allie ~ ~ ~` → She is removed at once. This matches 1.12: Allie removes herself when she has no wish in progress, so she only exists through the lamp.
- [ ] 2.13 `/summon sexmod:manglelie ~ ~ ~` → She spawns and stays (checked on the dev server).
- [ ] 2.14 `/summon sexmod:friendly_slime` and `/summon sexmod:pyrocinical`. → A small slime / a wandering girl. Hitting Pyrocinical freezes her, then she disappears after about 6 s.
- [ ] 2.15 Kill a Jenny, Ellie and Bia in survival. → Jenny drops 1-4 porkchop. Ellie drops 1-4 beef. Bia drops string or brown wool.

---

## 3. Shared companion menu (test on Jenny; it is the same for Ellie, Bia and Luna)

Right-click opens the menu. The left side has narrow icon buttons that slide open on hover.

- [ ] 3.1 Hover the left icons. → Labels: "Follow me", "Go home", "Set new home", "Equipment".
- [ ] 3.2 Follow me. → She follows you, teleports to you when more than about 30 blocks away, and the button becomes "Stop following me".
- [ ] 3.3 While she follows you, spawn a zombie. → She fights it with her sword (defaults: iron sword + bow).
- [ ] 3.4 Damage her to 0 HP while she follows you (e.g. let zombies hit her). → She goes into a DOWNED pose instead of dying.
- [ ] 3.5 Stop following me. → She stops following you and doesn't teleport.
- [ ] 3.6 Walk somewhere, open the menu → Set new home. → Chat: "Alright, this is my new Home~".
- [ ] 3.7 Walk away with her, then menu → Go home. → She throws an ender pearl and teleports home with portal particles.
- [ ] 3.8 Equipment. → 6 girl slots (weapon, bow, helmet, chest, legs, boots) plus your inventory. Wrong item types are refused.
- [ ] 3.9 Give her armour and a different sword. → Rendered on her model.
- [ ] 3.10 As a second "player" (or after Follow me): right-click someone else's following Jenny. → "I am busy at the moment~" (only testable with 2 players; optional).
- [ ] 3.11 Rename her with a name tag. → The name shows and one tag is used (outside creative). A name tag that was never renamed names her "Name Tag".
- [ ] 3.12 While a girl is in a bed scene, try to break that bed. → Blocked, with the chat line "this bed is currently used by a girl..".

---

## 4. Jenny / Ellie

**Jenny.** Menu buttons: Blowjob, Boobjob, Doggy, Strip (or Dress up when nude). Survival costs: Blowjob 3 Emerald, Boobjob 2 Ender Pearl, Doggy 2 Diamond, Strip 1 Gold Ingot.

- [ ] 4.1 Survival without the items, click Blowjob. → "Jenny you cannot afford that..." plus a sad sound.
- [ ] 4.2 Survival with 3 emeralds split as 1+2 across two stacks, click Blowjob. → Note what happens. The client wants one stack of 3, and the server takes from the first stack. (unverified)
- [ ] 4.3 Survival with the items: each option plays PAYMENT, then the scene.
- [ ] 4.4 Strip. → STRIP plays and she becomes nude. Then "Dress up" (free). → Dressed again.
- [ ] 4.5 Blowjob: Sneak triggers the thrust stage. Finish with Space. → Fade to black, then reset.
- [ ] 4.6 Boobjob (while dressed). → She strips first, then PAIZURI. Sneak = fast. Finish works.
- [ ] 4.7 Doggy with a bed within about 20 blocks. → She walks to the bed and waits. Step within 0.5 blocks of her. → Scene starts. Sneak = fast. Finish works.
- [ ] 4.8 Doggy with no bed nearby. → She does nothing, with a sound and maybe a chat line. (known bug? the "no bed" chat line is probably never shown)
- [ ] 4.9 Splash a horny potion on Jenny, then open the menu. → No costs, and scenes skip PAYMENT.

**Ellie.** Menu: "Face fuck" when dressed, "Dress up" when nude, plus the left icons. No costs.

- [ ] 4.10 "Face fuck". → CARRY intro, then slow stage after about 10 s. Sneak = fast. Finish works and she resets.
- [ ] 4.11 After her first scene, watch her for a minute. → Does she still wander and look at you? (known bug? her wander AI may stay removed)
- [ ] 4.12 Splash horny potion on Ellie within 10 blocks of you. → She dashes at you, hugs you, and a second menu opens with "Cowgirl" / "Missionary".
- [ ] 4.13 Choose Cowgirl. → She walks to a bed and sits. Come within 1.5 blocks. → COWGIRL scene. Sneak = fast. Finish works.
- [ ] 4.14 Repeat 4.12 and choose Missionary. → MISSIONARY scene to the end.
- [ ] 4.15 Repeat 4.12 and press Esc on the second menu. → She stays in the hug idle. R-Shift gets you out.
- [ ] 4.16 Ellie with no bed (after 4.12). → Chat "no bed in sight...".

---

## 5. Bia

Menu: Strip / Dress up, Talk, Head pat. No costs.

- [ ] 5.1 Head pat. → HEAD_PAT plays, then she resets.
- [ ] 5.2 Strip / Dress up. → Outfit toggles.
- [ ] 5.3 Talk. → Talk animation, then a second menu: "Anal", "doggy" (no left icons).
- [ ] 5.4 Talk → Esc on the second menu. → Scene cancels cleanly.
- [ ] 5.5 Talk → Anal, with a bed nearby. → She walks to the bed, strips and waits. Come within 1 block. → After a short delay the scene starts. Sneak = fast. Finish works.
- [ ] 5.6 Talk → doggy, with a bed that has a clear side. → She sits on the bed. Come within 1 block. → PRONE_DOGGY. Sneak moves through the stages (insert, soft, hard). Finish works.
- [ ] 5.7 Do Talk / Head pat 5+ times in a row. → She still behaves normally (no jitter or speed-up from stacked AI goals). (unverified)

---

## 6. Slime girl

Right-click does nothing by design: no menu, no follow, no inventory.

- [ ] 6.1 Right-click a Slime girl. → Nothing happens, no error.
- [ ] 6.2 Splash a horny potion on her. → She undresses and hops toward you (if you're within 30 blocks).
- [ ] 6.3 Let her reach you while you stand on the ground. → Scene starts (no intro). Sneak = fast. Finish works.
- [ ] 6.4 Afterwards → witch particles. About 2 min later (2400 ticks) a small friendly Slime spawns.
- [ ] 6.5 (Long, optional) Wait about 7 min (8400 ticks) near the small slime. → It turns into a new Slime girl. Particles near the end.
- [ ] 6.6 Natural route without the potion: watch her for a few minutes. → She eventually undresses and then comes for you on her own. Later she may start STARTDOGGY with no bed.

---

## 7. Bee

Untamed: no menu. Taming happens through her scene.

- [ ] 7.1 Right-click an untamed Bee. → Nothing.
- [ ] 7.2 Splash a horny potion on her, stay within 10 blocks. → She flies to you and the CITIZEN scene starts. Sneak = fast. Finish works.
- [ ] 7.3 After the scene. → About 10 s later, either hearts (tamed: she now wears a chest) or angry particles (not tamed). It's a 50/50 chance; repeat if needed.
- [ ] 7.4 Tamed Bee: right-click her. → Dialogue screen with Follow me, Go home, a small home icon, and a chest icon.
- [ ] 7.5 Follow me. → "The bee looks exited", she follows you. Stop following me. → "The bee looks sad".
- [ ] 7.6 Click the small icon button. → "The bee likes its new home". Then Go home. → Pearl throw, she goes home.
- [ ] 7.7 Click the chest icon. → Should open a 27-slot chest. (known bug? the icon checks the wrong flag, so it may show as untamed and do nothing)
- [ ] 7.8 Without the potion: spawn a Bee and wait about 80 s nearby. → She approaches you and starts the scene on her own.

---

## 8. Luna

Menu: sex (3 Cod), touch boobs (2 Salmon), Head pat (free), plus the left icons.

- [ ] 8.1 Right-click Luna. → Menu with the 3 options and the left icons.
- [ ] 8.2 touch boobs. → She positions herself, PAYMENT, then the scene. Sneak = fast. Finish works.
- [ ] 8.3 sex, with a bed nearby. → PAYMENT, walks to the bed and waits. Stand within about 1.25 blocks. → Sitting cowgirl scene. Sneak = fast. Finish works.
- [ ] 8.4 sex with no bed. → She says a chat line and the scene stops.
- [ ] 8.5 Head pat. → HEAD_PAT plays.
- [ ] 8.6 Survival, completely full inventory → Head pat. → Note whether it says "cannot afford". (known bug?)
- [ ] 8.7 Equipment. → Luna's own menu: weapon, bow, 4 armour, plus a fishing-rod slot (defaults: iron axe, fishing rod). Shift-click works.
- [ ] 8.8 Put Luna (no master, so don't click Follow me) next to water in an ocean, river, beach or swamp. → She casts a rod and a hook appears in the water. She reels in, then eats fish or throws other items away.
- [ ] 8.9 After a Luna sex scene, put her by water again. → Does she still fish? (known bug? fishing may stay off for the rest of the session)
- [ ] 8.10 Creepers nearby. → Creepers avoid Luna.

---

## 9. Galath

Wild Galath only targets survival players. Use `/gamemode survival` and stay above 10 HP. Have `/gamemode creative` ready.

- [ ] 9.1 Spawn a wild Galath, switch to survival within 20 blocks. → Boss bar. She flies around and attacks (sword, energy-ball dragons).
- [ ] 9.2 Shoot a dragon/energy ball with an arrow. → It is destroyed. Hit a dragon in melee. → It flies back at her and explodes. → She is knocked out to the ground, then stands up.
- [ ] 9.3 Let her pounce you on the ground. → Scene locks you and the escape HUD appears.
- [ ] 9.4 Escape HUD: press the WASD key shown each time. → Bar fills. At full, release the keys. → She backs off and flies away.
- [ ] 9.5 (Optional) Don't escape. → At about 1 HP she finishes and kills you.
- [ ] 9.6 Bring her HP to 0. → She doesn't die. "Galath is paralyzed!" and she stays knocked out.
- [ ] 9.7 Right-click her while she is knocked out on the ground. → CORRUPT scene. At the end she gives you a **Galath coin** in your hand and you are her master.
- [ ] 9.8 `/kill @e[type=sexmod:galath]`, then `/give @s sexmod:galath_coin`, right-click with it. → 4 s summon with particles, then an owned Galath appears 2 blocks ahead. (10% chance she does a different intro and starts a scene on you about 8 s later.)
- [ ] 9.9 Right-click the coin again while she exists. → Blocked (sound only).
- [ ] 9.10 Aim at your own Galath's body and right-click her with the coin. → Sound; about 1 s later she spreads her wings and shrinks away in a burst of purple particles, a particle stream flows into the coin and the coin darkens; after 4 s she is gone and no new Galath appears. (Fixed: she used to reappear next to you.)
- [ ] 9.10a Right after 9.10, right-click the coin in the air. → She is summoned again as in 9.8.
- [ ] 9.10b Click her with the coin several times during the de-summon. → It still finishes after 4 s.
- [ ] 9.11 Owned Galath, on the ground, right-click her with an empty hand. → Menu: "cowgirl", "anal", "ride" (+ "threesome" if you have a Manglelie).
- [ ] 9.12 cowgirl. → Scene after about 1 s. Finish works. Note that Sneak does nothing in Galath scenes (by design in the code).
- [ ] 9.13 anal. → CORRUPT scene to the end.
- [ ] 9.14 ride. → You mount her. WASD steers in your look direction. Space = boost (3 charges, HUD shows them). Sneak dismounts and the HUD fades.
- [ ] 9.15 While airborne (flying in creative), right-click her. → Only "ride" is offered.
- [ ] 9.16 Owned Galath with zombies nearby. → She fights mobs within about 7 blocks.
- [ ] 9.17 Walk more than 60 blocks away or change dimension. → She (and her Manglelie) are removed and you lose ownership (expected per code).
- [ ] 9.18 Kill your owned Galath. → De-summon animation, then she disappears.
- [ ] 9.19 `/futa true`, then a Galath scene. `/futa false`. → Model changes. No error (needs the `run/sexmod/` folder; note any "write failed").

---

## 10. Manglelie

- [ ] 10.1 Right-click a wild Manglelie. → Nothing (no menu).
- [ ] 10.2 Spawn-egg Manglelie within 15 blocks of an owned, grounded Galath. → Galath runs to her, HUG_MANG plays (about 12 s), then Manglelie rides on Galath's head.
- [ ] 10.3 Paired: spawn a zombie nearby. → Manglelie shoots arrows at it from Galath's head.
- [ ] 10.4 Paired: Galath menu → threesome. → Scene with both girls to the end.
- [ ] 10.5 (Optional) Sleep in a bed with a paired Galath + Manglelie nearby and wake up. → Morning scene (needs free space beside the bed). (unverified)
- [ ] 10.6 Dismiss Galath and use the coin again. → Your Manglelie comes back with her.

---

## 11. Allie (Allies Lamp)

The lamp has 3 wishes per item. The tooltip shows "2 wishes left", "1 wish left", or "no wishes left".

- [ ] 11.1 `/give @s sexmod:allies_lamp`, check the tooltip. Right-click. → Rub animation, HUD hidden, particles, Allie appears after about 5 s.
- [ ] 11.2 First use. → Long intro dialogue, then a menu: "Make me rich!", "Deepthroat!", "Reverse cowgirl".
- [ ] 11.3 Close the wish menu with Esc. → It reopens.
- [ ] 11.4 Make me rich! → 1-2 each of diamond, emerald and gold ingot drop at her position. She leaves and the HUD comes back.
- [ ] 11.5 Second use: Deepthroat!. → Short intro, then the scene. Sneak = fast. Finish works. She disappears afterwards.
- [ ] 11.6 Third use: Reverse cowgirl. → Scene to the end.
- [ ] 11.7 Fourth use. → Lamp is used up ("no wishes left") and nothing is summoned.
- [ ] 11.8 New lamp, stand on sand and use it. → Sand dialogue, Allie leaves, one wish used.
- [ ] 11.9 Use the lamp while transformed into a girl. → Blocked.
- [ ] 11.10 After every Allie exit (natural end and R-Shift). → No leftover dim screen, HUD visible, chat works.
- [ ] 11.11 Lamp in loot: check a few dungeon or mineshaft chests in a new world (optional). → Allies Lamp sometimes appears.

---

## 12. Goblin

- [ ] 12.1 Right-click a spawned goblin. → She is picked up and sits on your shoulder. During the pick-up both of her eyes show (see 1.16).
- [ ] 12.2 Right-click a second goblin while carrying one. → "you are already carrying a Goblin".
- [ ] 12.3 Hold **G** with the goblin on your shoulder. → Pad appears. Move the mouse to the left half, release G. → In first person your arms wind up (empty hands, per the 1.12 code), she appears in your hand at the release and flies off where you aim; she lands and stands up. One goblin only, and none left hanging in front of the camera. (Fixed: the first-person pass reused the wrong bone visibility.)
- [ ] 12.3a Same throw in third person (F5). → Your body does the throw and she flies off. With an offline/dev account the body uses the Steve skin.
- [ ] 12.4 Thrown goblin: right-click her again. → Picked up again.
- [ ] 12.5 Hold G → right-upper area → release. → PAIZURI scene. Sneak = fast. Finish works.
- [ ] 12.6 Hold G → right-lower area → release. → NELSON scene to the end. Afterwards the camera stays in third-person front (expected; press F5 to fix it).
- [ ] 12.7 With the goblin on your shoulder, press R-Shift. → Same pad opens.
- [ ] 12.8 Owned goblin: hit her or splash her. → She takes no damage. `/kill` does remove her.
- [ ] 12.9 Owned goblin, change dimension (nether portal). → She comes with you.
- [ ] 12.10 Goblin lair: in a new world, go underground near spawn and run `/locatenearestgoblinlair`. → "goblin lair found at x y z" or "No nearby goblin lair found uwu" (it only finds lairs that are loaded). (unverified that lairs generate often enough)
- [ ] 12.11 At the lair, walk into the area in front of the queen's throne while on the ground. → Breeding scene. Afterwards the guards wait to be picked up. Pick one up; the other vanishes with "Farewell my knight".
- [ ] 12.12 Walk in again right after. → "still pregnant" message.
- [ ] 12.13 (Long, optional) Stay near the queen with a gold item in your inventory. → A thief goblin steals it ("I got your X hehe~"). Right-click her within 3.5 blocks → catch, then a menu: "use her" / "take ur stuff back".

---

## 13. Kobolds, tribe and Dragon Staff

Run `/time set day` first. The tribe stops working after the first nightfall until the time is reset (same as 1.12: it checks the total day counter, not the time of day).

- [ ] 13.1 `/give @s sexmod:tribe_egg`, right-click the ground. → 4 kobolds spawn, one with a crown (leader). All have the same colour.
- [ ] 13.2 `/give @s sexmod:dragon_staff` (or craft: end crystal / gold block / stick in a vertical column). Right-click air before you own a tribe. → Nothing.
- [ ] 13.3 Right-click a tribe kobold with the staff. → "Name Tribe" screen with a text box (max 15 characters) and a "set" button. An empty name does nothing.
- [ ] 13.4 Enter a name, click set. → Chat to everyone: "<you> formed the <name> Tribe" in the tribe colour. Kobolds follow you.
- [ ] 13.5 Right-click air with the staff (now that you own a tribe). → Radial icon menu, no text. Hold right-click, drag to a quadrant, release.
- [ ] 13.6 Top-left quadrant. → Toggles follow mode (kobolds stop or start following). Note: Esc also commits the highlighted quadrant.
- [ ] 13.7 Bottom-right quadrant. → Floating wool cubes around the staff switch between orbiting and pointing at members.
- [ ] 13.8 Right-click your own kobold with the staff. → Her 27-slot inventory opens (titled with her name).
- [ ] 13.9 Aim the staff at a bed or chest and right-click. → Menu with the bottom-left "mark" quadrant; the bed is not slept in and the chest does not open. (Fixed in the port: 1.21 dropped the click here before.)
- [ ] 13.10 Follow mode off, aim at a tree log → top-right quadrant. → Without enough marked beds: "Ur Tribe will only work for you, if everyone of them has a bed". With a marked bed per kobold: a kobold says "Ima fall this tree owo" and cuts the tree.
- [ ] 13.11 Aim at stone → top-right quadrant. → Same bed refusal (with a stray "s"). If it gets past that: "Ima go mine uwu". (known bug? mining never progresses past the first block)
- [ ] 13.12 Hold the staff near a wild (unclaimed) tribe within 10 blocks. → Its kobolds stop, look at you and walk up to you.
- [ ] 13.13 Hit a tribe kobold in survival (not your tribe). → Its tribe attacks you.
- [ ] 13.14 Right-click a kobold with an empty hand. → Menu with raw labels "anal" / "oral" (+ "mating" for the master).
- [ ] 13.15 Survival, as a non-master: anal costs 3 gold ingots, oral costs 1 iron pickaxe. → Payment taken, PAYMENT plays, then the scene.
- [ ] 13.16 oral (master, or with a splash potion). → Scene to the end. Sneak = next stage.
- [ ] 13.17 anal. → KOBOLD_ANAL scene to the end.
- [ ] 13.18 mating (master). → Scene to the end. About 6 s in, you receive a Kobold egg in the tribe colour.
- [ ] 13.19 Right-click the ground with that egg. → An egg entity is placed. Wiggling speeds up over 10 minutes (12000 ticks), then it hatches: particles, sound, a new kobold, and chat "X has become a new tribe member!". Long; `/tick sprint 12000` speeds it up.
- [ ] 13.20 `/give @s sexmod:kobold_egg_item`, place it, let it hatch (or tick sprint). → A tribeless (purple) kobold. Watch the log for "tribe ... not found" spam. (known bug?)
- [ ] 13.21 Hit an egg entity. → It breaks and drops nothing.
- [ ] 13.22 Name-tag a kobold as her master. → Renamed, tag used.

---

## 14. Player forms (horny potion)

- [ ] 14.1 Brew: Water bottle + Redstone → Mundane Potion. Add a Poppy. → Potion of horny. (Also in the vanilla Food & Drinks tab.)
- [ ] 14.2 Drink it. → Nausea. About 5 s later the picker opens with `<`, `>`, `pick`. Order: Jenny, Ellie, Bia, Slime, Bee, Allie, Luna, Kobold, Goblin, Galath, then your own player. Manglelie is not listed.
- [ ] 14.3 Effect icon in the inventory and top-right HUD while it's active. → The vanilla Speed boot icon, as in 1.12.
- [ ] 14.4 Transform into **each** form in turn (drink again each time). → You see the form's model (third person F5), animations play, your armour shows on it, and swinging plays an attack animation.
- [ ] 14.5 For each form: open your inventory (E). → Jenny, Ellie, Bia, Slime and Galath have a Strip / Dress up button that works. Others have no button.
- [ ] 14.6 As Jenny: sneak + right-click a bed. → She gets into doggy position on the bed and waits.
- [ ] 14.7 As Ellie: sneak + right-click a bed. → Sits on the bed.
- [ ] 14.8 As Luna: sneak + right-click a bed. → WAIT_CAT pose.
- [ ] 14.9 As Bia: sneak + right-click a bed. → Menu "anal" / "doggy", then she goes to the bed.
- [ ] 14.10 Sneak + right-click with a fully blocked bed. → "Bed is obscured".
- [ ] 14.11 As Slime: sneak + right-click a block with an empty hand, looking down, within 3 blocks. → Doggy start pose.
- [ ] 14.12 As Bee and as Allie (survival): double-tap Space. → You can fly. Fall damage is 0 (also for Slime). Revert. → Flight removed.
- [ ] 14.13 As Galath (survival): fly, then revert. → Check whether you can still fly after reverting. (known bug? flight is probably not removed)
- [ ] 14.14 As any form, take damage (lava, mob). → The form can't be hurt (you still can).
- [ ] 14.15 Die as a form and respawn. → The form follows you to the respawn point.
- [ ] 14.16 Turn back: drink another potion, pick the last entry (your own player). → Normal player again.
- [ ] 14.17 In the picker press Esc. → Closes with no change (the potion is wasted).
- [ ] 14.18 Throw a splash horny potion at a villager and at a cow next to you. → The cow goes into love mode. Villager: unclear. (unverified)

---

## 15. Items

- [ ] 15.1 `sexmod:allies_lamp`: covered in 11.
- [ ] 15.2 `sexmod:galath_coin`: covered in 9.8-9.10.
- [ ] 15.3 `sexmod:tribe_egg`, `sexmod:dragon_staff`, `sexmod:kobold_egg_item`: covered in 13.
- [ ] 15.4 `sexmod:luna_rod` ("Luna's rod", creative tab only). Use it to fish yourself. → Works like a vanilla fishing rod.
- [ ] 15.5 Put `luna_rod` (or an enchanted rod) in Luna's rod slot. → No error. Her fishing uses it.
- [ ] 15.6 `sexmod:npc_editor_wand` ("Girl wand"). Craft it: gold ingot top-right, sticks in the middle and bottom-left. → Recipe works.
- [ ] 15.7 Hold the Girl wand and look at a girl. → The wand model switches to its "active" look.
- [ ] 15.8 Right-click a girl with the wand. → Customisation (reskin) browser opens, filtered to her type (or "No models found" if you have no packs).
- [ ] 15.9 Left-click a girl with the wand. → Chat "<name>'s model-code: …" and "copied to clipboard".
- [ ] 15.10 Left-click air with the wand while not transformed. → "you gotta turn into the girl, you want to copy the model-code off". While transformed. → Your form's code is copied.
- [ ] 15.11 `/give @s sexmod:winchester`. → Item exists (not in the tab). It probably renders invisible or as a missing model and does nothing (placeholder, as in 1.12).
- [ ] 15.12 `/give @s sexmod:item_map_secret`. → Unknown item (expected: only a lang entry exists).
- [ ] 15.13 Hold a vanilla filled map in first person while transformed. → Drawn with the girl's hand. (unverified)

---

## 16. Commands and customisation menu

- [ ] 16.1 Numpad 5. → "No models found." with Download / View on Github / No. (If you have packs, the reskin browser opens.)
- [ ] 16.2 Download (needs internet). → Downloads the custom-models archive from GitHub into `run/sexmod/custom_models/singleplayer`. Then Numpad 5 opens the browser.
- [ ] 16.3 Reskin browser: filter by girl, search, double-click a pack to apply, Apply, Done. → The girl's model changes in-world.
- [ ] 16.4 Browser: Remove, Revert All. → The model goes back to default.
- [ ] 16.5 Browser: select a girl filter, then Legacy and Body. → The old clothing screen and the body sliders screen open. Without a filter: "Select a girl chip first...".
- [ ] 16.6 `/reloadcustommodels`. → Reloads without errors.
- [ ] 16.7 Look at a girl: `/setmodelcode <code>` (use a code copied with the wand in 15.9). → Applied. Without a target and not transformed. → "You gotta transform into the girl you want to apply the model-code to".
- [ ] 16.8 `/futa` with no argument. → Usage message. `/futa true` / `/futa false` work (see 9.19).
- [ ] 16.9 `/whitelistserver` in singleplayer. → "This is a multiplayer feature only".
- [ ] 16.10 `/locatenearestgoblinlair` in the Nether. → "don't exist in the Nether" message.
- [ ] 16.11 First world join with internet. → The update check runs quietly (at most a chat notice). No crash when offline.

---

## 17. Worldgen (fresh world)

Houses are custom placements, not vanilla structures, so **`/locate structure` will not find them**. Use a new world (Default type, not Superflat) and explore with `/gamemode spectator` or by teleporting. A house is placed whenever the biome matches and the ground is flat enough, at least about 2.5k blocks from a house of the same type and about 1k blocks from any other house.

- [ ] 17.1 Jenny house: plains or forest. Small 9x9 house with Jenny inside. → Blocks look right (flower pots, beds, banners, skulls OK), and Jenny is present.
- [ ] 17.2 Ellie house: taiga, snowy taiga, old-growth pine taiga, or dark forest. Large 30x26 building with Ellie.
- [ ] 17.3 Bia house: birch forest. Bia is inside.
- [ ] 17.4 Luna house: ocean or deep ocean. Small structure on the water with Luna.
- [ ] 17.5 Goblin lair: underground cavity with a throne and a queen (see 12.10).
- [ ] 17.6 Kobold camps: wild 4-kobold tribes at about 0.4% of new chunks. → Seen while exploring. At night their leader builds a netherrack + fire camp.
- [ ] 17.7 Natural spawns: Bee in forest, Slime girl in swamp, Manglelie and Pyrocinical in the Nether, Galath replacing an occasional wither skeleton or blaze in the Nether. → Seen at least once each (optional, rare).
- [ ] 17.8 Flying fast through new terrain. → No lag spikes or "Can't keep up" bursts tied to house placement.
- [ ] 17.9 `run/config/sexmod.json`: set `"shouldGenBuildings":false` (no spaces after the colon), restart, new world. → No houses.

---

## 18. Persistence (Save & Quit, reload)

Set this up first: a named Jenny following you with armour and a custom home, a tamed Bee, a claimed kobold tribe with items in one kobold, an owned Galath, a goblin on your shoulder, and you transformed into a form.

- [ ] 18.1 Reload. → All girls present. Custom names kept.
- [ ] 18.2 Jenny: equipment kept, still following you, Go home still goes to the custom home.
- [ ] 18.3 Outfit: a stripped girl is dressed again after reload (expected: outfit is not saved).
- [ ] 18.4 Bee still tamed (has her chest).
- [ ] 18.5 Kobold tribe: same name, colour, master and members. Kobold inventory kept. Follow mode is OFF after reload (expected).
- [ ] 18.6 Unhatched egg entity keeps its age (it doesn't restart at 0).
- [ ] 18.7 Owned Galath still yours. Note whether she's there or must be re-summoned with the coin.
- [ ] 18.8 Goblin still owned and on your shoulder, or near you.
- [ ] 18.9 You are still in your girl form after reload.
- [ ] 18.10 Applied reskins (16.3) are still applied.
- [ ] 18.11 The log has no exceptions during save or load. Files `sexmod_*.dat` / `tribes.dat` exist in `run/saves/<world>/data/`.

---

## 19. Multiplayer (optional)

Use a second client, e.g. a second `./gradlew runClient` with another username, or another machine. Start it via Open to LAN, or with `./gradlew runServer` and then `./gradlew runClient -PquickPlay=localhost:25565`.

- [ ] 19.1 `./gradlew runServer` starts to "Done". → No crash from client classes loading on the server.
- [ ] 19.2 Both players see the same girls, animations and scenes. Player B watches A's Jenny scene. → It renders in sync.
- [ ] 19.3 Player A transforms (e.g. Jenny). Player B (normal) right-clicks A. → A's form menu (Jenny: Blowjob / Boobjob) opens for B. B picks one. → A sees "<B> asked you for a …" and "[ Yes | No ]". A types `Yes` in chat. → Scene plays for both.
- [ ] 19.4 Same as 19.3 but type `No`. → Declined.
- [ ] 19.5 Request sent to a normal (untransformed) player and ignored for more than 1 minute. → Note whether it ever expires. (known bug? the timeout may never count down on their side)
- [ ] 19.6 Form menus to try via 19.3: Ellie "Face fuck", Bia "Head pat", Slime "Blowjob", Bee "sex", Allie "Deepthroat!" / "Reverse cowgirl", Luna "touch boobs" / "Head pat", Kobold "anal" / "oral" / "mating", Goblin "anal" / "paizuri", Galath "cowgirl" / "mating press" / "ride". → Each plays. Galath "ride" is expected to do nothing (known bug?).
- [ ] 19.7 Player-Jenny bed wait (14.6) and player B walks within 1 block. → Scene starts.
- [ ] 19.8 Two transformed players right-click each other. → "no lesbo yet owo".
- [ ] 19.9 Player B tries to use A's following Jenny. → "I am busy at the moment~".
- [ ] 19.10 `/whitelistserver`, then `/whitelistserver confirm` on the dedicated server. → "confirmed :)". The server's custom models download to the client.

---

## 20. General / regressions

- [ ] 20.1 After every scene type above, confirm movement, camera and HUD are normal. Note any scene that leaves you stuck, floating or in the wrong camera.
- [ ] 20.2 During a scene, try to hurt the player with a mob. → The player near their scene partner takes no damage.
- [ ] 20.3 Check the log after a session for repeated warnings (especially `ClientServerCheck`, missing animation or sound, GeckoLib Molang errors). → Note anything that repeats.
- [ ] 20.4 Leave a world and join another without restarting. → No girls or state carried over, and no crash.
