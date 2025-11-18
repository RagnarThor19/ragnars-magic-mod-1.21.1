package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

public class RecallingSpell implements Spell {

    /** Data for a single saved recall point. */
    private static class RecallPoint {
        final net.minecraft.registry.RegistryKey<World> worldKey;
        final BlockPos pos;

        RecallPoint(net.minecraft.registry.RegistryKey<World> worldKey, BlockPos pos) {
            this.worldKey = worldKey;
            this.pos = pos;
        }
    }

    // One recall point per player
    private static final Map<UUID, RecallPoint> RECALL_POINTS = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    public RecallingSpell() {
        // Register server tick once when the spell class is first created
        if (!TICK_REGISTERED) {
            TICK_REGISTERED = true;
            ServerTickEvents.END_SERVER_TICK.register(RecallingSpell::onServerTick);
        }
    }

    // Spell interface: return true if something happened
    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staffStack) {
        if (world.isClient) return false;

        UUID id = player.getUuid();
        RecallPoint existing = RECALL_POINTS.get(id);

        if (existing == null) {
            // FIRST PRESS: set a recall point at player's feet
            BlockPos base = BlockPos.ofFloored(player.getX(), player.getY(), player.getZ());
            RECALL_POINTS.put(id, new RecallPoint(world.getRegistryKey(), base));

            world.playSound(null, base, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return true;
        } else {
            // SECOND PRESS: recall if same dimension
            if (!(world instanceof ServerWorld serverWorld)) return false;

            ServerWorld targetWorld = serverWorld.getServer().getWorld(existing.worldKey);
            if (targetWorld == null) {
                RECALL_POINTS.remove(id);
                return false;
            }

            if (player instanceof net.minecraft.server.network.ServerPlayerEntity serverPlayer) {
                double x = existing.pos.getX() + 0.5;
                double y = existing.pos.getY();
                double z = existing.pos.getZ() + 0.5;

                serverPlayer.teleport(targetWorld, x, y, z, player.getYaw(), player.getPitch());
                targetWorld.playSound(null, existing.pos, SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
            }

            // Clear the mark after recall
            RECALL_POINTS.remove(id);
            return true;
        }
    }

    /** Tick handler for marker particles. */
    private static void onServerTick(MinecraftServer server) {
        if (RECALL_POINTS.isEmpty()) return;

        for (RecallPoint point : RECALL_POINTS.values()) {
            ServerWorld world = server.getWorld(point.worldKey);
            if (world == null) continue;

            Vec3d center = new Vec3d(point.pos.getX() + 0.5, point.pos.getY() + 0.2, point.pos.getZ() + 0.5);

            // Small, subtle particle pillar that "stays" there
            // Cool ember-like marker effect
            for (int i = 0; i < 4; i++) {
                double dx = (world.random.nextDouble() - 0.5) * 0.2;
                double dz = (world.random.nextDouble() - 0.5) * 0.2;
                double dy = world.random.nextDouble() * 0.3;

                // Small magical fire flickers
                world.spawnParticles(
                        net.minecraft.particle.ParticleTypes.FLAME,
                        center.x + dx,
                        center.y + dy,
                        center.z + dz,
                        1,
                        0.0, 0.01, 0.0,
                        0.0001
                );

                // Soft smoke rising
                world.spawnParticles(
                        net.minecraft.particle.ParticleTypes.SMOKE,
                        center.x + dx,
                        center.y + dy + 0.1,
                        center.z + dz,
                        1,
                        0.0, 0.02, 0.0,
                        0.0
                );

                // Rare spark for magical highlight
                if (world.random.nextFloat() < 0.10f) {
                    world.spawnParticles(
                            net.minecraft.particle.ParticleTypes.CRIT,
                            center.x,
                            center.y + 0.2,
                            center.z,
                            1,
                            0.0, 0.03, 0.0,
                            0.0
                    );
                }
            }

        }
    }
}
