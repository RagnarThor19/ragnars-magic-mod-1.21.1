package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.TntEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.network.TntPayloads;
import net.ragnar.ragnarsmagicmod.util.TntArc;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of TNT. The first cast readies a block of TNT and shows where it will land (the scroll wheel throws it
 * nearer or further); the second cast lobs it, lit to go off just after it lands. Real TNT: it breaks the
 * landscape.
 */
public class TntSpell implements Spell {
    private static final int AIM_TIMEOUT = 20 * 15;
    private static final int ARM_COOLDOWN = 6; // stops a double-click from throwing straight away

    private static final class Aim {
        float power = TntArc.DEFAULT_POWER;
        int ticksLeft = AIM_TIMEOUT;
    }

    private static final Map<UUID, Aim> AIMING = new HashMap<>();
    private static boolean registered = false;

    private static void ensureRegistered() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> {
            Iterator<Map.Entry<UUID, Aim>> it = AIMING.entrySet().iterator();
            while (it.hasNext()) {
                Map.Entry<UUID, Aim> e = it.next();
                ServerPlayerEntity p = server.getPlayerManager().getPlayer(e.getKey());
                if (p == null) { it.remove(); continue; }
                boolean stillAiming = p.isAlive() && --e.getValue().ticksLeft > 0 && holdingTntTome(p);
                if (!stillAiming) {
                    it.remove();
                    TntPayloads.sendAiming(p, false, 0f);
                    p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.BLOCK_FIRE_EXTINGUISH,
                            SoundCategory.PLAYERS, 0.4f, 1.6f);
                }
            }
        });
    }

    private static boolean holdingTntTome(PlayerEntity p) {
        for (ItemStack stack : new ItemStack[]{p.getMainHandStack(), p.getOffHandStack()}) {
            if (stack.getItem() instanceof StaffItem && StaffItem.getSelectedTome(stack) == ModItems.TOME_OF_TNT) return true;
        }
        return false;
    }

    private static boolean isAiming(PlayerEntity player) {
        return !player.getWorld().isClient && AIMING.containsKey(player.getUuid());
    }

    /** Readying it is free; the XP is paid on the throw. */
    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        if (player.getWorld().isClient) return 0;
        return isAiming(player) ? tomeCost : 0;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return isAiming(player) ? ARM_COOLDOWN : tomeCooldown;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;
        ensureRegistered();

        Aim aim = AIMING.remove(player.getUuid());
        if (aim == null) {
            // First cast: take out the TNT and show where it'll go
            Aim fresh = new Aim();
            AIMING.put(player.getUuid(), fresh);
            TntPayloads.sendAiming(sp, true, fresh.power);
            sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_CROSSBOW_LOADING_MIDDLE.value(),
                    SoundCategory.PLAYERS, 0.8f, 0.7f);
            sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ITEM_FLINTANDSTEEL_USE,
                    SoundCategory.PLAYERS, 0.5f, 0.8f);
            player.sendMessage(Text.literal("Scroll to aim nearer or further • Cast again to throw"), true);
            return true;
        }

        // Second cast: throw it
        TntArc.Flight flight = TntArc.simulate(sw, player, aim.power);
        Vec3d start = TntArc.start(player);
        TntEntity tnt = new TntEntity(sw, start.x, start.y, start.z, player);
        tnt.setVelocity(TntArc.velocity(player, aim.power));
        tnt.setFuse(TntArc.fuseFor(flight));
        sw.spawnEntity(tnt);
        TntPayloads.sendAiming(sp, false, 0f);

        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_TNT_PRIMED, SoundCategory.PLAYERS, 1.0f, 1.0f);
        sw.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.ENTITY_WITCH_THROW, SoundCategory.PLAYERS, 1.0f, 0.6f);
        sw.spawnParticles(ParticleTypes.SMOKE, start.x, start.y + 0.5, start.z, 6, 0.1, 0.1, 0.1, 0.02);
        sw.spawnParticles(ParticleTypes.FLAME, start.x, start.y + 0.9, start.z, 3, 0.05, 0.05, 0.05, 0.01);
        return true;
    }

    /** The wheel changed the throw strength (client -> server). */
    public static void onPower(ServerPlayerEntity player, float power) {
        Aim aim = AIMING.get(player.getUuid());
        if (aim == null) return;
        aim.power = TntArc.clampPower(power);
        aim.ticksLeft = AIM_TIMEOUT;
    }
}
