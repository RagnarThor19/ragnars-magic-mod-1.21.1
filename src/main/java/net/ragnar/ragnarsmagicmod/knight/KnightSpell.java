package net.ragnar.ragnarsmagicmod.knight;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.Tameable;
import net.minecraft.entity.decoration.ArmorStandEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.entity.boss.BossBar;
import net.minecraft.entity.boss.ServerBossBar;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.Spell;
import net.ragnar.ragnarsmagicmod.network.ShakePayload;
import net.ragnar.ragnarsmagicmod.util.Aim;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Knight: an ancient knight climbs out of the ground in front of you and guards you for a minute.
 * While it's out, casting again is free and skips the cooldown:
 * - at a creature: the knight hunts it down (charging at it if it's far)
 * - at nothing: it drops what it's doing and falls back to you
 * - while sneaking: it kneels and sinks back into the earth
 * A boss bar shows its health and time left, and the tome's cooldown only starts once it's gone.
 */
public class KnightSpell implements Spell {
    public static final int LIFETIME_TICKS = 20 * 60;
    private static final int WARN_TICKS = 20 * 5;

    private static final class Summon {
        final UUID owner;
        final KnightEntity knight;
        final ServerBossBar bar;
        int ticks;

        Summon(UUID owner, KnightEntity knight) {
            this.owner = owner;
            this.knight = knight;
            this.bar = new ServerBossBar(title(LIFETIME_TICKS), BossBar.Color.YELLOW, BossBar.Style.NOTCHED_10);
        }
    }

    /** Knights by caster. */
    private static final Map<UUID, Summon> SUMMONS = new HashMap<>();

    public static void register() {
        ServerTickEvents.END_SERVER_TICK.register(KnightSpell::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            SUMMONS.values().forEach(s -> s.bar.clearPlayers());
            SUMMONS.clear();
        });
    }

    static boolean isLive(KnightEntity knight) {
        Summon s = knight.getOwnerUuid().map(SUMMONS::get).orElse(null);
        return s != null && s.knight == knight;
    }

    private static boolean hasKnight(PlayerEntity player) {
        if (player.getWorld().isClient) {
            // The client has no summon list; a knight of ours in sight will do
            return !player.getWorld().getEntitiesByClass(KnightEntity.class, player.getBoundingBox().expand(64.0),
                    k -> k.isOwnedBy(player)).isEmpty();
        }
        return SUMMONS.containsKey(player.getUuid());
    }

    // Once the knight is out, ordering it around costs nothing and ignores the cooldown
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return hasKnight(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return hasKnight(player) ? 0 : tomeCost;
    }

    // The cooldown only starts once the knight is gone (see finish)
    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0;
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (!(world instanceof ServerWorld sw) || !(player instanceof ServerPlayerEntity sp)) return false;

        Summon s = SUMMONS.get(player.getUuid());
        if (s == null) return summon(sw, sp);

        KnightEntity knight = s.knight;
        if (knight.isDismissing()) return false;
        if (player.isSneaking()) {
            knight.dismiss();
            return true;
        }
        var aimed = Aim.target(sw, player, KnightEntity.COMMAND_RANGE, 4.0, e -> e instanceof LivingEntity living
                && e != knight && !(e instanceof ArmorStandEntity)
                && !(e instanceof Tameable pet && player.getUuid().equals(pet.getOwnerUuid()))
                && knight.isFoe(living, player));
        if (aimed instanceof LivingEntity target) {
            knight.command(target);
        } else {
            knight.recall();
        }
        return true;
    }

    // ---------------------------------------------------------------------
    // Summoning
    // ---------------------------------------------------------------------

    private static boolean summon(ServerWorld world, ServerPlayerEntity player) {
        KnightEntity knight = Knight.KNIGHT.create(world);
        if (knight == null) return false;

        Vec3d at = findSpot(world, player, knight);
        if (at == null) {
            player.sendMessage(Text.literal("Not enough room for the Knight to rise.").formatted(Formatting.GRAY), true);
            world.playSound(null, player.getBlockPos(), SoundEvents.BLOCK_FIRE_EXTINGUISH, SoundCategory.PLAYERS, 0.6f, 1.0f);
            return false;
        }

        knight.setOwner(player);
        knight.refreshPositionAndAngles(at.x, at.y, at.z, player.getYaw(), 0f);
        knight.setHeadYaw(player.getYaw());
        knight.setBodyYaw(player.getYaw());
        Summon s = new Summon(player.getUuid(), knight);
        SUMMONS.put(player.getUuid(), s); // before spawning, so its first tick sees it's live
        world.spawnEntity(knight);
        knight.rise();
        s.bar.addPlayer(player);

        world.playSound(null, at.x, at.y, at.z, SoundEvents.ENTITY_WARDEN_EMERGE, SoundCategory.PLAYERS, 1.4f, 1.1f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.BLOCK_BELL_RESONATE, SoundCategory.PLAYERS, 1.0f, 0.5f);
        world.playSound(null, at.x, at.y, at.z, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), SoundCategory.PLAYERS, 2.0f, 0.6f);
        // The ground splits open in a ring of soul fire
        for (int i = 0; i < 36; i++) {
            double a = i * Math.PI * 2 / 36;
            world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, at.x + Math.cos(a) * 1.6, at.y + 0.1, at.z + Math.sin(a) * 1.6,
                    0, -Math.cos(a) * 0.4, 0.25, -Math.sin(a) * 0.4, 0.3);
        }
        world.spawnParticles(ParticleTypes.SOUL, at.x, at.y + 0.5, at.z, 10, 0.6, 0.3, 0.6, 0.03);
        ShakePayload.around(world, at, 6.0, 0.45f, 16);
        return true;
    }

    /** Somewhere in front of the caster with solid ground and three blocks of headroom. */
    private static Vec3d findSpot(ServerWorld world, PlayerEntity player, KnightEntity knight) {
        float[] offsets = {0f, 35f, -35f, 70f, -70f, 110f, -110f, 180f};
        for (double r : new double[]{3.0, 2.0}) {
            for (float off : offsets) {
                double a = Math.toRadians(player.getYaw() + off);
                Vec3d base = player.getPos().add(-Math.sin(a) * r, 0, Math.cos(a) * r);
                // Allow a step up or down from the caster's feet
                for (int dy : new int[]{0, 1, -1}) {
                    Vec3d at = new Vec3d(base.x, Math.floor(player.getY()) + dy, base.z);
                    BlockPos below = BlockPos.ofFloored(at).down();
                    if (!world.getBlockState(below).isSideSolidFullSquare(world, below, Direction.UP)) continue;
                    if (world.isSpaceEmpty(knight, knight.getDimensions(knight.getPose()).getBoxAt(at))) return at;
                }
            }
        }
        return null;
    }

    // ---------------------------------------------------------------------
    // Ticking
    // ---------------------------------------------------------------------

    private static void tick(MinecraftServer server) {
        if (SUMMONS.isEmpty()) return;
        Iterator<Summon> it = SUMMONS.values().iterator();
        while (it.hasNext()) {
            Summon s = it.next();
            KnightEntity knight = s.knight;
            ServerPlayerEntity owner = server.getPlayerManager().getPlayer(s.owner);

            // Gone: sank back down, killed, or unloaded
            if (knight.isRemoved() || !knight.isAlive()) {
                it.remove();
                finish(s, owner);
                continue;
            }
            // Caster left the world or the dimension: it simply fades
            if (owner == null || owner.getWorld() != knight.getWorld()) {
                knight.discard();
                it.remove();
                finish(s, owner);
                continue;
            }
            if (!owner.isAlive()) knight.dismiss();

            s.ticks++;
            int left = LIFETIME_TICKS - s.ticks;
            if (left <= 0) knight.dismiss();

            s.bar.setPercent(knight.getHealth() / knight.getMaxHealth());
            if (s.ticks % 20 == 0 || left == WARN_TICKS) {
                s.bar.setName(title(Math.max(0, left)));
                s.bar.setColor(left <= WARN_TICKS ? BossBar.Color.RED : BossBar.Color.YELLOW);
            }
            if (!s.bar.getPlayers().contains(owner)) s.bar.addPlayer(owner);
        }
    }

    private static Text title(int ticksLeft) {
        return Text.translatable("entity.ragnarsmagicmod.knight")
                .append(Text.literal("  " + (ticksLeft + 19) / 20 + "s").formatted(Formatting.GRAY));
    }

    /** The knight is gone: drop the boss bar and only now start the tome's cooldown. */
    private static void finish(Summon s, PlayerEntity owner) {
        s.bar.clearPlayers();
        if (owner == null) return;
        TomeItem tome = Knight.TOME_OF_KNIGHT;
        ItemStack staff = StaffItem.findStaffWith(owner, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(owner.getWorld(), owner, staff, tome, tome.getCooldown());
        else owner.getItemCooldownManager().set(tome, tome.getCooldown());
    }
}
