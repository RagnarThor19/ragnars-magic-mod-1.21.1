package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.SpawnReason;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.RegistryKey;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.RaycastContext;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.entity.ModEntities;
import net.ragnar.ragnarsmagicmod.entity.SteveEntity;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

public class SummonSteveSpell implements Spell {

    private static final double RANGE = 24.0;
    private static final int DELAY_TICKS = 120; // 2 seconds

    // Tracking pending summons per world
    private static final Map<RegistryKey<World>, List<PendingSummon>> PENDING = new HashMap<>();
    private static boolean TICK_REGISTERED = false;

    private static void ensureTicker() {
        if (TICK_REGISTERED) return;
        ServerTickEvents.END_WORLD_TICK.register(SummonSteveSpell::tickWorld);
        TICK_REGISTERED = true;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        ensureTicker();

        // Raycast to find ground
        HitResult hit = world.raycast(new RaycastContext(
                player.getEyePos(),
                player.getEyePos().add(player.getRotationVec(1.0f).multiply(RANGE)),
                RaycastContext.ShapeType.COLLIDER,
                RaycastContext.FluidHandling.NONE,
                player
        ));

        if (hit.getType() != HitResult.Type.BLOCK) {
            // Play a fail sound if pointing at sky
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 1.0f, 1.0f);
            return false;
        }

        Vec3d targetPos = hit.getPos();
        // Move slightly up so he doesn't spawn in the floor
        targetPos = targetPos.add(0, 1.0, 0);

        // Initial cue sound
        world.playSound(null, BlockPos.ofFloored(targetPos),
                SoundEvents.BLOCK_PORTAL_TRIGGER, SoundCategory.PLAYERS, 0.8f, 0.6f);

        ServerWorld sw = (ServerWorld) world;
        PENDING.computeIfAbsent(sw.getRegistryKey(), k -> new ArrayList<>())
                .add(new PendingSummon(targetPos, sw.getTime() + DELAY_TICKS));

        return true;
    }

    private static void tickWorld(ServerWorld world) {
        List<PendingSummon> list = PENDING.get(world.getRegistryKey());
        if (list == null || list.isEmpty()) return;

        long now = world.getTime();
        Iterator<PendingSummon> it = list.iterator();

        while (it.hasNext()) {
            PendingSummon summon = it.next();

            // 1. Spawn cool particle cloud while waiting
            spawnSummonParticles(world, summon.pos);

            // 2. Check if time to spawn
            if (now >= summon.spawnTime) {
                spawnSteve(world, summon.pos);
                it.remove();
            }
        }
    }

    private static void spawnSummonParticles(ServerWorld world, Vec3d pos) {
        // Swirling portal particles
        for (int i = 0; i < 4; i++) {
            double offsetX = (world.random.nextDouble() - 0.5) * 1.5;
            double offsetY = (world.random.nextDouble() - 0.5) * 1.5;
            double offsetZ = (world.random.nextDouble() - 0.5) * 1.5;
            world.spawnParticles(ParticleTypes.PORTAL,
                    pos.x + offsetX, pos.y + offsetY, pos.z + offsetZ,
                    1, 0, 0, 0, 1.0);
        }
        // Dense cloud at the center
        world.spawnParticles(ParticleTypes.CLOUD,
                pos.x, pos.y, pos.z,
                2, 0.3, 0.5, 0.3, 0.05);
    }

    private static void spawnSteve(ServerWorld world, Vec3d pos) {
        SteveEntity steve = ModEntities.STEVE.create(world);
        if (steve != null) {
            steve.refreshPositionAndAngles(pos.x, pos.y, pos.z, world.random.nextFloat() * 360f, 0);
            steve.initialize(world, world.getLocalDifficulty(BlockPos.ofFloored(pos)), SpawnReason.MOB_SUMMONED, null);
            world.spawnEntity(steve);

            // Thunder/Explosion sound for dramatic entry
            world.playSound(null, BlockPos.ofFloored(pos),
                    SoundEvents.ENTITY_LIGHTNING_BOLT_THUNDER, SoundCategory.PLAYERS, 1.0f, 1.0f);

            // Explosion particles (visual only)
            world.spawnParticles(ParticleTypes.EXPLOSION, pos.x, pos.y + 1, pos.z, 1, 0, 0, 0, 0);
        }
    }

    private record PendingSummon(Vec3d pos, long spawnTime) {}
}