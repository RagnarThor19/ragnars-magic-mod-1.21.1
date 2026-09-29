# Original staff look (before the 3D staffs)

These are the staff models and textures as they were before the redesign.

To go back to them:

1. Copy `models/item/*.json` and `textures/item/*.png` from this folder over the ones in
   `src/main/resources/assets/ragnarsmagicmod/`.
2. Delete the redesign's extra files:
   - `src/main/resources/assets/ragnarsmagicmod/models/item/golden_staff_inventory.json`
   - `src/main/resources/assets/ragnarsmagicmod/models/item/diamond_staff_inventory.json`
   - `src/main/resources/assets/ragnarsmagicmod/models/item/netherite_staff_inventory.json`
   - `src/main/resources/assets/ragnarsmagicmod/textures/item/staff_shaft.png`
   - `src/main/java/net/ragnar/ragnarsmagicmod/client/StaffModels.java`
   - `src/main/java/net/ragnar/ragnarsmagicmod/mixin/client/ItemRendererMixin.java`
3. Remove `"client.ItemRendererMixin",` from `src/main/resources/ragnarsmagicmod.mixins.json` and the
   `StaffModels.init();` line from `RagnarsMagicModClient.java`.
