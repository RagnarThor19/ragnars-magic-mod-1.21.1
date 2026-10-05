package net.ragnar.ragnarsmagicmod.sight;

import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.BlockState;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Tome of Sight: finds every ore within {@link Sight#RADIUS} of the caster's eyes and hands the list to the caster's
 * client, which plays the wave and the reveal (see SightClient). Everyone nearby hears it go out and sees a ring of
 * glints at the caster's feet; only the caster sees what it found.
 */
public class SightSpell implements Spell {
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw)) return false;
        Vec3d eye = player.getEyePos();
        List<Sight.Found> ores = scan(sw, eye);
        if (player instanceof ServerPlayerEntity sp && ServerPlayNetworking.canSend(sp, Sight.ScanPayload.ID)) {
            ServerPlayNetworking.send(sp, new Sight.ScanPayload(eye.toVector3f(), ores));
        }

        sw.playSound(null, eye.x, eye.y, eye.z, SoundEvents.ITEM_SPYGLASS_USE, SoundCategory.PLAYERS, 1.4f, 0.6f);
        sw.playSound(null, eye.x, eye.y, eye.z, SoundEvents.BLOCK_VAULT_ACTIVATE, SoundCategory.PLAYERS, 1.0f, 1.3f);
        sw.playSound(null, eye.x, eye.y, eye.z, SoundEvents.UI_CARTOGRAPHY_TABLE_TAKE_RESULT, SoundCategory.PLAYERS, 0.9f, 0.7f);
        // A ring of glints skating out across the floor
        Vec3d feet = player.getPos().add(0, 0.1, 0);
        for (int i = 0; i < 40; i++) {
            double a = Math.PI * 2 * i / 40;
            sw.spawnParticles(ParticleTypes.SCRAPE, feet.x, feet.y, feet.z, 0, Math.cos(a), 0, Math.sin(a), 0.9);
        }
        return true;
    }

    /** Every ore whose centre is within {@link Sight#RADIUS} of {@code origin}, nearest first. */
    public static List<Sight.Found> scan(World world, Vec3d origin) {
        int r = MathHelper.ceil(Sight.RADIUS);
        BlockPos o = BlockPos.ofFloored(origin);
        double r2 = Sight.RADIUS * Sight.RADIUS;
        List<Sight.Found> found = new ArrayList<>();
        BlockPos.Mutable m = new BlockPos.Mutable();
        for (int dx = -r; dx <= r; dx++) {
            for (int dy = -r; dy <= r; dy++) {
                for (int dz = -r; dz <= r; dz++) {
                    m.set(o.getX() + dx, o.getY() + dy, o.getZ() + dz);
                    if (Vec3d.ofCenter(m).squaredDistanceTo(origin) > r2 || world.isOutOfHeightLimit(m)) continue;
                    BlockState state = world.getBlockState(m);
                    OreKind kind = OreKind.of(state);
                    if (kind != null) found.add(new Sight.Found(m.toImmutable(), kind));
                }
            }
        }
        found.sort(Comparator.comparingDouble(f -> Vec3d.ofCenter(f.pos()).squaredDistanceTo(origin)));
        return found.size() > Sight.MAX_ORES ? new ArrayList<>(found.subList(0, Sight.MAX_ORES)) : found;
    }
}
