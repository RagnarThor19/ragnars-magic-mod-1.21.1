package net.ragnar.ragnarsmagicmod.item.spell;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.minecraft.entity.attribute.EntityAttributeInstance;
import net.minecraft.entity.attribute.EntityAttributeModifier;
import net.minecraft.entity.attribute.EntityAttributes;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.Box;
import net.minecraft.world.World;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.StaffItem;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.util.FairyForm;

import java.util.HashMap;
import java.util.Iterator;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Shrinking and Tome of Growing: you become tiny or huge for {@link #DURATION_TICKS}. Only your size changes
 * (hearts, speed and reach stay the same; giants also get Strength I), using the game's own scale attribute so
 * everyone sees it. Cast again to
 * go back early. You never grow into blocks: growing needs room, and if you're tiny somewhere too low to stand when
 * time runs out, you stay tiny until there's space. The tome's cooldown starts once you're normal size again.
 */
public class SizeSpell implements Spell {
    public static final int DURATION_TICKS = 20 * 120;
    /** Tiny: 0.45 blocks tall, just under the half-block gap beneath a slab. */
    public static final float SMALL_SCALE = 0.25f;
    /** Huge: exactly 6 blocks tall (a player is 1.8). */
    public static final float LARGE_SCALE = 6.0f / 1.8f;

    private static final Identifier MODIFIER_ID = Identifier.of(RagnarsMagicMod.MOD_ID, "size_spell");

    public enum Kind {
        SMALL(SMALL_SCALE), LARGE(LARGE_SCALE);

        final float scale;

        Kind(float scale) {
            this.scale = scale;
        }

        TomeItem tome() {
            return this == SMALL ? ModItems.TOME_OF_SHRINKING : ModItems.TOME_OF_GROWING;
        }
    }

    private static final class Sized {
        final PlayerEntity player;
        final Kind kind;
        int left = DURATION_TICKS;
        float scale = 1f;
        float target;
        boolean ending;
        boolean toldNoRoom;

        Sized(PlayerEntity player, Kind kind) {
            this.player = player;
            this.kind = kind;
            this.target = kind.scale;
        }
    }

    private static final Map<UUID, Sized> SIZED = new HashMap<>();
    private static boolean registered;

    private final Kind kind;

    public SizeSpell(Kind kind) {
        this.kind = kind;
    }

    public static void register() {
        if (registered) return;
        registered = true;
        ServerTickEvents.END_SERVER_TICK.register(server -> tick());
        // The size is a temporary modifier: it's never saved, so a player who leaves just comes back normal
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> SIZED.remove(handler.player.getUuid()));
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Sized s : SIZED.values()) {
                setScale(s.player, 1f);
                takeStrength(s.player);
            }
            SIZED.clear();
        });
    }

    /** True while this tome's size is on you (either side: the client sees its own scale). */
    private boolean isActive(PlayerEntity player) {
        if (player.getWorld().isClient) {
            float scale = player.getScale();
            return kind == Kind.SMALL ? scale < 0.95f : scale > 1.05f;
        }
        Sized s = SIZED.get(player.getUuid());
        return s != null && s.kind == kind;
    }

    // Casting again turns you back: free, and allowed while the tome is cooling down
    @Override
    public boolean ignoresCooldown(PlayerEntity player) {
        return isActive(player);
    }

    @Override
    public int xpCost(PlayerEntity player, int tomeCost) {
        return isActive(player) ? 0 : tomeCost;
    }

    @Override
    public int cooldownAfterCast(PlayerEntity player, int tomeCooldown) {
        return 0; // starts when you're back to normal (see tick)
    }

    @Override
    public boolean cast(World world, PlayerEntity player, ItemStack staff) {
        if (world.isClient) return false;
        Sized current = SIZED.get(player.getUuid());
        if (current != null) {
            if (current.kind != kind) {
                player.sendMessage(Text.literal("Go back to normal size first.").formatted(Formatting.GRAY), true);
                return false;
            }
            // Asking to turn back counts as time being up: if there's no room yet, it keeps trying until there is
            if (!current.ending) {
                current.left = 0;
                beginEnd(current);
            }
            return true;
        }
        if (FairyForm.isFairy(player)) {
            player.sendMessage(Text.literal("Not while you're a fairy.").formatted(Formatting.GRAY), true);
            return false;
        }
        if (!fits(player, kind.scale)) {
            player.sendMessage(Text.literal("Not enough room to grow here.").formatted(Formatting.RED), true);
            return false;
        }

        SIZED.put(player.getUuid(), new Sized(player, kind));
        if (kind == Kind.LARGE) giveStrength(player);
        boolean small = kind == Kind.SMALL;
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                small ? SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE : SoundEvents.ENTITY_IRON_GOLEM_REPAIR,
                SoundCategory.PLAYERS, 1.0f, small ? 1.8f : 0.6f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(),
                SoundEvents.ENTITY_ILLUSIONER_CAST_SPELL, SoundCategory.PLAYERS, 0.6f, small ? 1.6f : 0.7f);
        poof(player);
        return true;
    }

    /** Would the player fit, standing where they are, at this scale? */
    private static boolean fits(PlayerEntity player, float scale) {
        Box box = player.getBaseDimensions(player.getPose()).scaled(scale).getBoxAt(player.getPos());
        return player.getWorld().isSpaceEmpty(player, box.contract(1.0E-6));
    }

    private static void beginEnd(Sized s) {
        // Tiny under something low: wait for room rather than growing into the blocks
        if (s.kind == Kind.SMALL && !fits(s.player, 1f)) {
            if (!s.toldNoRoom) {
                s.player.sendMessage(Text.literal("No room to grow back. You will once there's space.").formatted(Formatting.YELLOW), true);
                s.toldNoRoom = true;
            }
            return;
        }
        s.ending = true;
        s.target = 1f;
        takeStrength(s.player);
        PlayerEntity p = s.player;
        p.getWorld().playSound(null, p.getX(), p.getY(), p.getZ(), SoundEvents.ENTITY_ILLUSIONER_MIRROR_MOVE,
                SoundCategory.PLAYERS, 0.8f, s.kind == Kind.SMALL ? 1.5f : 0.8f);
        poof(p);
    }

    private static void tick() {
        if (SIZED.isEmpty()) return;
        Iterator<Sized> it = SIZED.values().iterator();
        while (it.hasNext()) {
            Sized s = it.next();
            PlayerEntity player = s.player;
            boolean gone = player.isRemoved() && (player.getRemovalReason() == null || player.getRemovalReason().shouldDestroy());
            if (gone || !player.isAlive()) {
                it.remove();
                setScale(player, 1f);
                takeStrength(player);
                startCooldown(player, s.kind);
                continue;
            }
            if (!s.ending) {
                if (s.left > 0) s.left--;
                // Three falling chimes over the last three seconds
                if (s.left == 60 || s.left == 40 || s.left == 20) {
                    player.getWorld().playSound(null, player.getX(), player.getY(), player.getZ(),
                            SoundEvents.BLOCK_NOTE_BLOCK_CHIME.value(), SoundCategory.PLAYERS, 0.8f,
                            s.left == 60 ? 1.6f : s.left == 40 ? 1.3f : 1.0f);
                }
                if (s.left <= 0) beginEnd(s);
                // Drank milk? Giants keep their strength for as long as they're big
                if (s.kind == Kind.LARGE && !s.ending) giveStrength(player);
            }

            // Ease towards the target size over a few ticks
            float next = s.scale + (s.target - s.scale) * 0.35f;
            if (Math.abs(s.target - next) < 0.01f) next = s.target;
            if (next != s.scale) {
                s.scale = next;
                setScale(player, next);
            }
            if (s.ending && s.scale == 1f) {
                it.remove();
                setScale(player, 1f);
                startCooldown(player, s.kind);
            }
        }
    }

    private static void setScale(PlayerEntity player, float scale) {
        EntityAttributeInstance attr = player.getAttributeInstance(EntityAttributes.GENERIC_SCALE);
        if (attr == null) return;
        attr.removeModifier(MODIFIER_ID);
        if (scale != 1f) {
            attr.addTemporaryModifier(new EntityAttributeModifier(MODIFIER_ID, scale - 1f,
                    EntityAttributeModifier.Operation.ADD_MULTIPLIED_TOTAL));
        }
    }

    /**
     * Strength I while giant. Ours never runs out (it's taken away when you shrink back), which also marks it as ours,
     * so a Strength potion you drank is never stepped on or removed.
     */
    private static void giveStrength(PlayerEntity player) {
        if (player.hasStatusEffect(StatusEffects.STRENGTH)) return;
        player.addStatusEffect(new StatusEffectInstance(StatusEffects.STRENGTH, StatusEffectInstance.INFINITE, 0, false, false, true));
    }

    private static void takeStrength(PlayerEntity player) {
        StatusEffectInstance strength = player.getStatusEffect(StatusEffects.STRENGTH);
        if (strength != null && strength.isInfinite() && strength.getAmplifier() == 0) player.removeStatusEffect(StatusEffects.STRENGTH);
    }

    private static void startCooldown(PlayerEntity player, Kind kind) {
        TomeItem tome = kind.tome();
        ItemStack staff = StaffItem.findStaffWith(player, tome);
        if (!staff.isEmpty()) StaffItem.applyCooldown(player.getWorld(), player, staff, tome, tome.getCooldown());
        else player.getItemCooldownManager().set(tome, tome.getCooldown());
    }

    private static void poof(PlayerEntity player) {
        if (player.getWorld() instanceof ServerWorld sw) {
            sw.spawnParticles(ParticleTypes.POOF, player.getX(), player.getY() + player.getHeight() / 2, player.getZ(),
                    16, player.getWidth() * 0.6, player.getHeight() * 0.4, player.getWidth() * 0.6, 0.02);
        }
    }

    /** For the game tests: skip ahead to the last few ticks. */
    public static void setTicksLeft(PlayerEntity player, int ticks) {
        Sized s = SIZED.get(player.getUuid());
        if (s != null) s.left = ticks;
    }
}
