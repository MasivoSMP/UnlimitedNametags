# WorldGuard nametag visibility

UnlimitedNameTags registers two WorldGuard state flags when WorldGuard is installed. Both apply to existing
WorldGuard regions; they do not create or resize regions.

The region command separates the two effects:

```text
/unt visibility region <world> <region> others hide|show|clear
/unt visibility region <world> <region> self hide|show|clear
```

`others` hides or allows other players' nametags from a viewer standing inside the region. `self` hides or allows
the viewer's own nametag from themselves. The
original `/unt visibility region <world> <region> hide|show|clear`
form remains an alias for `others`. `clear` removes the flag value and inherits the normal WorldGuard result.
The command suggests loaded worlds and region IDs and requires `unt.visibility.admin` (operators by default).

The flags can also be managed directly with WorldGuard commands:

```text
/rg flag <region> unt-see-other-nametags deny
/rg flag <region> unt-see-other-nametags allow
/rg flag <region> unt-see-other-nametags
/rg flag <region> unt-see-own-nametag deny
/rg flag <region> unt-see-own-nametag allow
/rg flag <region> unt-see-own-nametag
```

WorldGuard handles region priorities, overlaps, and inheritance. `unt-see-other-nametags deny` hides both UNT's
custom nametag rows and the vanilla player name above other players, matching `/unt hideOtherNametags true`;
`unt-see-own-nametag deny` hides the viewer's own nametag from themselves. An `allow` value removes that region's
denial, after which UNT's normal global settings and player preferences apply. An unset flag has no effect. The
`others` rule respects the viewer's personal preferences and administrator hide restriction. The `self` rule only
affects the player's own nametag as seen by that player.
Leaving the effective deny area restores the applicable settings. Region state is transient and is not saved to player data; the
separate `/unt visibility player <name> hide|show` administrator restriction remains available.

The integration uses WorldGuard session callbacks when the effective flag changes, including the initial
session state. It reconciles only the affected player's tracked nametags, on that player's scheduler, when the
flag changes or a session is initialized. It does not poll every player or scan all regions every tick. Current
sessions are reset after a flag edit so players already inside receive the new effective value; reconnecting
players receive it from their initial session state.

WorldGuard's `/rg flags` output uses fixed colors for explicit, inherited, and unset values; custom plugins
cannot choose a per-flag color there. UNT's region command feedback is light green instead. See the
[WorldGuard region flag documentation](https://worldguard.enginehub.org/en/latest/regions/commands/) and
[custom flag/session handler API](https://worldguard.enginehub.org/en/latest/developer/regions/custom-flags/).
