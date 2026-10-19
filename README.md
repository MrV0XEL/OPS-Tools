<div align="center">
  <h1><strong><u>OPS Tools</u></strong></h1>
  <p><strong>Utility and automation addon for <a href="https://meteorclient.com/">Meteor Client</a></strong></p>
  <p><strong>OPS Tools</strong> is a collection of powerful automation and utility modules for Minecraft Java Edition.</p>
</div>

---

## <u>Features:</u>

### **Auto Wither+ (`AutoWither.java`)**
* **Multiple Wither Support:** Configurable `wither-count` setting to build up to 10 Withers sequentially per activation.
* **Auto-Nametagging:** Automatically renames newly spawned Withers using a custom `wither-name` string and nametags from your inventory/hotbar.
* **Smart Material Validation:** Counts and verifies required Soul Sand/Soul Soil (4 per Wither) and Wither Skeleton Skulls (3 per Wither) before starting and rechecks them before every subsequent structure. Supports Creative infinite-material mode.
* **Rotation & Alignment Locks:** Lock build directions to cardinal points (North, South, East, West) or match player yaw orientation.
* **Visual 3D Previews:** Live 3D render preview boxes indicating target placements and fill/outline customizations.
* **Safety & Reliability:** Prevents duplicate building, checks block obstructions/entities to avoid misplacement, safely restores player hotbar selection slots, and handles disconnections or dimension changes gracefully.

---

## <u>Credits:</u>
- [Meteor Client](https://github.com/meteordevelopment/meteor-client)
- [Germanminer / MeteorServerUtils](https://github.com/Germanminer/MeteorServerUtils) (Original AutoWither implementation base)

---

## <u>Requirements:</u>
- Meteor Client [https://meteorclient.com/](https://meteorclient.com/)
- Fabric API [https://github.com/FabricMC/fabric-api](https://github.com/FabricMC/fabric-api)
