# WorldGuard nametag visibility

UnlimitedNameTags registers the WorldGuard state flag `unt-see-other-nametags` when WorldGuard is installed.
The flag applies to an existing WorldGuard region. It does not create or resize regions.

Use `/unt visibility region <world> <region> hide` to hide other players' nametags from viewers while they are
inside the region. Use `show` to explicitly allow them, or `clear` to remove the region's value and inherit the
normal WorldGuard result. The command suggests loaded worlds and region IDs and requires
`unt.visibility.admin` (operators by default).

The same flag can be managed with WorldGuard commands:

```text
/rg flag <region> unt-see-other-nametags deny
/rg flag <region> unt-see-other-nametags allow
/rg flag <region> unt-see-other-nametags
```

WorldGuard handles region priorities, overlaps, and inheritance. `deny` hides other players' nametags; `allow`
shows them unless a higher-priority applicable WorldGuard rule denies them. An unset flag has no effect.
The viewer's personal preferences and any administrator hide restriction still apply. Leaving the effective
deny area restores those preferences; the region state is transient and is not saved to the player's data.

The integration uses WorldGuard session callbacks when the effective flag changes, including the initial
session state. Each change updates only nametags currently tracked by that viewer. It does not poll every
player or scan all regions every tick.

WorldGuard's `/rg flags` output uses fixed colors for explicit, inherited, and unset values; custom plugins
cannot choose a per-flag color there. UNT's region command feedback is light green instead. See the
[WorldGuard region flag documentation](https://worldguard.enginehub.org/en/latest/regions/commands/) and
[custom flag/session handler API](https://worldguard.enginehub.org/en/latest/developer/regions/custom-flags/).
