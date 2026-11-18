package net.ragnar.ragnarsmagicmod.blocktome;

import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.entity.effect.StatusEffects;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.particle.DustParticleEffect;
import net.minecraft.particle.ParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.tag.BlockTags;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.World;
import org.jetbrains.annotations.Nullable;
import org.joml.Vector3f;

import java.util.List;

/**
 * What a copied block does when it hits something. Most blocks are {@link #PLAIN}: a solid {@link BlockTome#DAMAGE}
 * thump. The rest carry a little of what they are: ice freezes, netherrack burns, slime bounces on to the next mob...
 * {@link #of} sorts a block into its trait; {@link #onHit} is the effect.
 */
public enum BlockTrait {
    PLAIN(1.0f, 1.0f, null),
    /** Ice, packed ice, blue ice: frozen solid (the powder-snow shiver) and slowed right down. */
    FROST(1.0f, 1.0f, ParticleTypes.SNOWFLAKE),
    /** Snow and powder snow: a chill and a cold slap of knockback. */
    SNOW(0.75f, 1.6f, ParticleTypes.SNOWFLAKE),
    /** Netherrack, magma, coal: sets them alight. */
    FLAME(1.0f, 1.0f, ParticleTypes.FLAME),
    /** Soul sand and soul soil: withers them and drags at their feet. */
    SOUL(1.0f, 1.0f, ParticleTypes.SOUL_FIRE_FLAME),
    /** Obsidian, iron, netherite: hits half again as hard and knocks them flying. */
    HEAVY(1.5f, 2.2f, null),
    /** Crying obsidian: heavy, and it saps their strength. */
    WEEPING(1.5f, 2.0f, ParticleTypes.DRIPPING_OBSIDIAN_TEAR),
    /** Bedrock: double damage and nothing stops it, it ploughs through every mob in its way. */
    BEDROCK(2.0f, 2.0f, ParticleTypes.SMOKE),
    /** TNT: goes off where it lands (hurts mobs, not the ground). */
    EXPLOSIVE(1.0f, 1.0f, ParticleTypes.SMOKE),
    /** Sand: grit in the eyes. */
    GRITTY(1.0f, 1.0f, null),
    /** Gravel: bursts into rubble that clips everything nearby. */
    RUBBLE(1.0f, 1.0f, null),
    /** Glass: shatters into shards that cut everything nearby. */
    GLASS(1.0f, 1.0f, null),
    /** Slime: bounces on to the next mob, up to three times, flinging each one up. */
    BOUNCY(1.0f, 1.0f, ParticleTypes.ITEM_SLIME),
    /** Honey, mud, clay: sticks them in place. */
    STICKY(1.0f, 0.4f, ParticleTypes.DRIPPING_HONEY),
    /** Cactus: poisoned by its spines. */
    THORNY(1.0f, 1.0f, null),
    /** Amethyst: rings out, a shockwave that staggers everything nearby. */
    RESONANT(1.0f, 1.0f, ParticleTypes.END_ROD),
    /** Sculk: wraps them in the Darkness. */
    SCULK(1.0f, 1.0f, ParticleTypes.SCULK_SOUL),
    /** End stone: blinks them somewhere else nearby, like a chorus fruit. */
    ENDER(1.0f, 1.0f, ParticleTypes.PORTAL),
    /** Purpur: floats them up. */
    FLOATY(1.0f, 1.0f, ParticleTypes.END_ROD),
    /** Copper: arcs on to the three nearest mobs. */
    CONDUCTIVE(1.0f, 1.0f, ParticleTypes.ELECTRIC_SPARK),
    /** Redstone: overloads in a burst of charged dust that hurts everything around. */
    REDSTONE(1.0f, 1.0f, new DustParticleEffect(new Vector3f(1f, 0.05f, 0.05f), 1.0f)),
    /** Gold: lights them up so you can see them through walls. */
    GILDED(1.0f, 1.0f, ParticleTypes.WAX_ON),
    /** Diamond and emerald: cuts clean through, up to three mobs in a row. */
    PRECIOUS(1.25f, 1.0f, ParticleTypes.WAX_OFF),
    /** Glowstone, sea lanterns, shroomlight, froglights: a flash that blinds everything nearby. */
    RADIANT(1.0f, 1.0f, ParticleTypes.END_ROD),
    /** Lapis and bookshelves: arcane, weakens them. */
    ARCANE(1.0f, 1.0f, ParticleTypes.ENCHANT),
    /** Mushroom blocks, mycelium, nylium, wart: spores, sickening and poisonous. */
    FUNGAL(1.0f, 1.0f, ParticleTypes.SPORE_BLOSSOM_AIR),
    /** Moss: a little of the hit comes back to you as health. */
    VERDANT(1.0f, 1.0f, ParticleTypes.HAPPY_VILLAGER),
    /** Prismarine and sponge: a wave that puts out fire, shoves hard and tires them like a guardian. */
    TIDAL(1.0f, 2.0f, ParticleTypes.BUBBLE),
    /** Wool and hay: soft, half damage, but it bowls them right over. */
    CUSHY(0.5f, 3.0f, null),
    /** Bone: brittle; leaves them weakened. */
    BONE(1.0f, 1.0f, null);

    /** Damage, times {@link BlockTome#DAMAGE}. */
    public final float damage;
    /** Knockback, times the usual. */
    public final float knockback;
    /** What drifts off it while it orbits and flies (null: nothing beyond the usual glint). */
    @Nullable public final ParticleEffect ambient;

    BlockTrait(float damage, float knockback, @Nullable ParticleEffect ambient) {
        this.damage = damage;
        this.knockback = knockback;
        this.ambient = ambient;
    }

    /** How many mobs it can pass through, beyond the first. */
    public int pierce() {
        return switch (this) {
            case BEDROCK -> 8;
            case PRECIOUS -> 2;
            default -> 0;
        };
    }

    /** How many times it carries on to another mob after a hit. */
    public int bounces() {
        return this == BOUNCY ? 3 : 0;
    }

    public static BlockTrait of(BlockState state) {
        Block b = state.getBlock();
        String path = Registries.BLOCK.getId(b).getPath();

        if (state.isIn(BlockTags.ICE)) return FROST;
        if (b == Blocks.SNOW_BLOCK || b == Blocks.POWDER_SNOW) return SNOW;
        if (b == Blocks.NETHERRACK || b == Blocks.MAGMA_BLOCK || b == Blocks.COAL_BLOCK || state.isIn(BlockTags.COAL_ORES)) return FLAME;
        if (b == Blocks.SOUL_SAND || b == Blocks.SOUL_SOIL) return SOUL;
        if (b == Blocks.CRYING_OBSIDIAN) return WEEPING;
        if (b == Blocks.OBSIDIAN || b == Blocks.IRON_BLOCK || b == Blocks.RAW_IRON_BLOCK || state.isIn(BlockTags.IRON_ORES)
                || b == Blocks.NETHERITE_BLOCK || b == Blocks.ANCIENT_DEBRIS) return HEAVY;
        if (b == Blocks.BEDROCK) return BEDROCK;
        if (b == Blocks.TNT) return EXPLOSIVE;
        if (state.isIn(BlockTags.SAND) || path.endsWith("sandstone")) return GRITTY;
        if (b == Blocks.GRAVEL) return RUBBLE;
        if (state.isIn(BlockTags.IMPERMEABLE) || path.endsWith("glass")) return GLASS;
        if (b == Blocks.SLIME_BLOCK) return BOUNCY;
        if (b == Blocks.HONEY_BLOCK || b == Blocks.MUD || b == Blocks.CLAY || b == Blocks.HONEYCOMB_BLOCK) return STICKY;
        if (b == Blocks.CACTUS) return THORNY;
        if (b == Blocks.AMETHYST_BLOCK || b == Blocks.BUDDING_AMETHYST) return RESONANT;
        if (b == Blocks.SCULK) return SCULK;
        if (path.startsWith("end_stone")) return ENDER;
        if (path.startsWith("purpur")) return FLOATY;
        if (path.contains("copper")) return CONDUCTIVE;
        if (b == Blocks.REDSTONE_BLOCK || state.isIn(BlockTags.REDSTONE_ORES)) return REDSTONE;
        if (b == Blocks.GOLD_BLOCK || b == Blocks.RAW_GOLD_BLOCK || state.isIn(BlockTags.GOLD_ORES) || b == Blocks.GILDED_BLACKSTONE) return GILDED;
        if (b == Blocks.DIAMOND_BLOCK || b == Blocks.EMERALD_BLOCK || state.isIn(BlockTags.DIAMOND_ORES) || state.isIn(BlockTags.EMERALD_ORES)) return PRECIOUS;
        if (b == Blocks.GLOWSTONE || b == Blocks.SEA_LANTERN || b == Blocks.SHROOMLIGHT || path.endsWith("froglight")
                || b == Blocks.JACK_O_LANTERN || b == Blocks.REDSTONE_LAMP) return RADIANT;
        if (b == Blocks.LAPIS_BLOCK || state.isIn(BlockTags.LAPIS_ORES) || b == Blocks.BOOKSHELF) return ARCANE;
        if (b == Blocks.MYCELIUM || b == Blocks.RED_MUSHROOM_BLOCK || b == Blocks.BROWN_MUSHROOM_BLOCK || b == Blocks.MUSHROOM_STEM
                || state.isIn(BlockTags.NYLIUM) || state.isIn(BlockTags.WART_BLOCKS)) return FUNGAL;
        if (b == Blocks.MOSS_BLOCK || b == Blocks.MOSSY_COBBLESTONE || b == Blocks.MOSSY_STONE_BRICKS) return VERDANT;
        if (path.contains("prismarine") || b == Blocks.SPONGE || b == Blocks.WET_SPONGE) return TIDAL;
        if (state.isIn(BlockTags.WOOL) || b == Blocks.HAY_BLOCK) return CUSHY;
        if (b == Blocks.BONE_BLOCK) return BONE;
        return PLAIN;
    }

    // ---------------------------------------------------------------------
    // The hit
    // ---------------------------------------------------------------------

    /**
     * {@code victim} was just hurt by a block of this trait (already damaged and knocked back). {@code at} is where it
     * hit, {@code dir} which way it was flying.
     */
    public void onHit(ServerWorld world, @Nullable PlayerEntity owner, LivingEntity victim, Vec3d at, Vec3d dir) {
        Vec3d c = victim.getBoundingBox().getCenter();
        switch (this) {
            case FROST -> {
                victim.setFrozenTicks(Math.max(victim.getFrozenTicks(), victim.getMinFreezeDamageTicks() + 120));
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 3), owner);
                victim.extinguish();
                world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 40, 0.4, 0.6, 0.4, 0.08);
                world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, c.x, c.y, c.z, 12, 0.3, 0.5, 0.3, 0.1);
                sound(world, c, SoundEvents.BLOCK_GLASS_BREAK, 0.9f, 1.4f);
                sound(world, c, SoundEvents.ENTITY_PLAYER_HURT_FREEZE, 1.0f, 1.0f);
            }
            case SNOW -> {
                victim.setFrozenTicks(Math.max(victim.getFrozenTicks(), victim.getMinFreezeDamageTicks() + 20));
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 1), owner);
                victim.extinguish();
                world.spawnParticles(ParticleTypes.ITEM_SNOWBALL, c.x, c.y, c.z, 25, 0.4, 0.5, 0.4, 0.15);
                world.spawnParticles(ParticleTypes.SNOWFLAKE, c.x, c.y, c.z, 15, 0.4, 0.5, 0.4, 0.05);
                sound(world, c, SoundEvents.BLOCK_SNOW_BREAK, 1.2f, 0.8f);
            }
            case FLAME -> {
                victim.setOnFireFor(6);
                world.spawnParticles(ParticleTypes.FLAME, c.x, c.y, c.z, 25, 0.35, 0.5, 0.35, 0.05);
                world.spawnParticles(ParticleTypes.LAVA, c.x, c.y, c.z, 5, 0.2, 0.3, 0.2, 0.0);
                sound(world, c, SoundEvents.ITEM_FIRECHARGE_USE, 0.9f, 1.0f);
            }
            case SOUL -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WITHER, 80, 0), owner);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 80, 1), owner);
                world.spawnParticles(ParticleTypes.SOUL_FIRE_FLAME, c.x, c.y, c.z, 25, 0.35, 0.5, 0.35, 0.04);
                world.spawnParticles(ParticleTypes.SOUL, c.x, c.y, c.z, 8, 0.3, 0.4, 0.3, 0.03);
                sound(world, c, SoundEvents.PARTICLE_SOUL_ESCAPE.value(), 2.0f, 0.8f);
                sound(world, c, SoundEvents.BLOCK_SOUL_SAND_BREAK, 1.0f, 0.7f);
            }
            case HEAVY -> {
                world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.4);
                sound(world, c, SoundEvents.BLOCK_ANVIL_LAND, 0.6f, 0.8f);
            }
            case WEEPING -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 120, 1), owner);
                world.spawnParticles(ParticleTypes.FALLING_OBSIDIAN_TEAR, c.x, c.y + 0.4, c.z, 15, 0.4, 0.3, 0.4, 0.0);
                world.spawnParticles(ParticleTypes.REVERSE_PORTAL, c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.1);
                sound(world, c, SoundEvents.BLOCK_ANVIL_LAND, 0.5f, 0.7f);
                sound(world, c, SoundEvents.BLOCK_RESPAWN_ANCHOR_DEPLETE.value(), 0.8f, 1.3f);
            }
            case BEDROCK -> {
                world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 25, 0.3, 0.4, 0.3, 0.5);
                world.spawnParticles(ParticleTypes.LARGE_SMOKE, c.x, c.y, c.z, 5, 0.2, 0.3, 0.2, 0.02);
                sound(world, c, SoundEvents.BLOCK_ANVIL_LAND, 0.8f, 0.5f);
            }
            case EXPLOSIVE -> explode(world, owner, at);
            case GRITTY -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 50, 0), owner);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 50, 0), owner);
                world.spawnParticles(ParticleTypes.WHITE_ASH, c.x, victim.getEyeY(), c.z, 30, 0.4, 0.3, 0.4, 0.02);
                sound(world, c, SoundEvents.BLOCK_SAND_BREAK, 1.2f, 0.7f);
            }
            case RUBBLE -> splash(world, owner, victim, at, 3.0, 3.0f, null);
            case GLASS -> {
                splash(world, owner, victim, at, 3.0, 4.0f, null);
                sound(world, at, SoundEvents.BLOCK_GLASS_BREAK, 1.3f, 0.8f);
            }
            case BOUNCY -> {
                victim.addVelocity(0, 0.7, 0);
                victim.velocityModified = true;
                world.spawnParticles(ParticleTypes.ITEM_SLIME, c.x, c.y, c.z, 25, 0.35, 0.4, 0.35, 0.15);
                sound(world, c, SoundEvents.BLOCK_SLIME_BLOCK_FALL, 1.3f, 1.0f);
                sound(world, c, SoundEvents.ENTITY_SLIME_JUMP, 1.0f, 1.2f);
            }
            case STICKY -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 100, 4), owner);
                world.spawnParticles(ParticleTypes.DRIPPING_HONEY, c.x, c.y + 0.5, c.z, 20, 0.4, 0.4, 0.4, 0.0);
                world.spawnParticles(ParticleTypes.FALLING_HONEY, c.x, c.y, c.z, 10, 0.4, 0.3, 0.4, 0.0);
                sound(world, c, SoundEvents.BLOCK_HONEY_BLOCK_SLIDE, 1.2f, 0.8f);
            }
            case THORNY -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 100, 1), owner);
                world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.3);
                sound(world, c, SoundEvents.ENCHANT_THORNS_HIT, 1.2f, 1.0f);
            }
            case RESONANT -> {
                ring(world, c, 3.5);
                for (LivingEntity e : nearby(world, owner, victim, at, 4.0)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2), owner);
                    Vec3d push = e.getPos().subtract(at).multiply(1, 0, 1);
                    if (push.lengthSquared() > 1e-4) {
                        push = push.normalize().multiply(0.6);
                        e.addVelocity(push.x, 0.25, push.z);
                        e.velocityModified = true;
                    }
                    hurtExtra(world, owner, e, 3.0f);
                }
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2), owner);
                sound(world, c, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2.0f, 1.0f);
                sound(world, c, SoundEvents.BLOCK_AMETHYST_CLUSTER_BREAK, 1.5f, 0.8f);
            }
            case SCULK -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.DARKNESS, 140, 0), owner);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 0), owner);
                world.spawnParticles(ParticleTypes.SCULK_SOUL, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.05);
                world.spawnParticles(ParticleTypes.SCULK_CHARGE_POP, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.05);
                sound(world, c, SoundEvents.BLOCK_SCULK_SHRIEKER_SHRIEK, 0.8f, 1.4f);
                sound(world, c, SoundEvents.BLOCK_SCULK_BREAK, 1.2f, 0.8f);
            }
            case ENDER -> {
                world.spawnParticles(ParticleTypes.PORTAL, c.x, c.y, c.z, 50, 0.4, 0.6, 0.4, 0.5);
                if (victim.getMaxHealth() <= 120 && victim.isAlive()) blink(world, victim);
            }
            case FLOATY -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.LEVITATION, 50, 1), owner);
                world.spawnParticles(ParticleTypes.END_ROD, c.x, c.y, c.z, 20, 0.3, 0.5, 0.3, 0.05);
                sound(world, c, SoundEvents.ENTITY_SHULKER_BULLET_HIT, 1.2f, 1.0f);
            }
            case CONDUCTIVE -> arc(world, owner, victim);
            case REDSTONE -> {
                splash(world, owner, victim, at, 3.0, 4.0f, REDSTONE.ambient);
                world.spawnParticles(REDSTONE.ambient, at.x, at.y, at.z, 40, 1.2, 0.8, 1.2, 0.0);
                sound(world, at, SoundEvents.BLOCK_REDSTONE_TORCH_BURNOUT, 1.5f, 0.8f);
                sound(world, at, SoundEvents.ENTITY_GENERIC_EXPLODE.value(), 0.4f, 1.8f);
            }
            case GILDED -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 300, 0), owner);
                world.spawnParticles(ParticleTypes.WAX_ON, c.x, c.y, c.z, 25, 0.4, 0.5, 0.4, 0.5);
                sound(world, c, SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, 2.0f, 1.6f);
            }
            case PRECIOUS -> {
                world.spawnParticles(ParticleTypes.WAX_OFF, c.x, c.y, c.z, 20, 0.3, 0.4, 0.3, 0.5);
                world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 12, 0.3, 0.4, 0.3, 0.4);
                sound(world, c, SoundEvents.BLOCK_AMETHYST_BLOCK_HIT, 2.0f, 1.8f);
            }
            case RADIANT -> {
                world.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
                world.spawnParticles(ParticleTypes.END_ROD, at.x, at.y, at.z, 40, 0.2, 0.2, 0.2, 0.25);
                for (LivingEntity e : nearby(world, owner, victim, at, 4.0)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0), owner);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0), owner);
                }
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0), owner);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0), owner);
                sound(world, at, SoundEvents.BLOCK_BEACON_ACTIVATE, 1.0f, 2.0f);
            }
            case ARCANE -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 120, 1), owner);
                world.spawnParticles(ParticleTypes.ENCHANT, c.x, c.y + 0.8, c.z, 60, 0.4, 0.6, 0.4, 1.0);
                world.spawnParticles(ParticleTypes.ENCHANTED_HIT, c.x, c.y, c.z, 25, 0.3, 0.4, 0.3, 0.3);
                sound(world, c, SoundEvents.BLOCK_ENCHANTMENT_TABLE_USE, 1.2f, 1.4f);
            }
            case FUNGAL -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.NAUSEA, 160, 0), owner);
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.POISON, 80, 0), owner);
                world.spawnParticles(ParticleTypes.SPORE_BLOSSOM_AIR, c.x, c.y, c.z, 30, 0.5, 0.6, 0.5, 0.02);
                world.spawnParticles(ParticleTypes.CRIMSON_SPORE, c.x, c.y, c.z, 20, 0.5, 0.6, 0.5, 0.02);
                sound(world, c, SoundEvents.BLOCK_FUNGUS_BREAK, 1.2f, 0.7f);
            }
            case VERDANT -> {
                if (owner != null && owner.isAlive()) {
                    owner.heal(2.0f);
                    Vec3d o = owner.getBoundingBox().getCenter();
                    world.spawnParticles(ParticleTypes.HEART, o.x, o.y + 0.6, o.z, 2, 0.3, 0.2, 0.3, 0.0);
                }
                world.spawnParticles(ParticleTypes.HAPPY_VILLAGER, c.x, c.y, c.z, 15, 0.4, 0.5, 0.4, 0.0);
                world.spawnParticles(ParticleTypes.FALLING_SPORE_BLOSSOM, c.x, c.y + 0.5, c.z, 10, 0.4, 0.3, 0.4, 0.0);
                sound(world, c, SoundEvents.BLOCK_MOSS_BREAK, 1.3f, 0.9f);
            }
            case TIDAL -> {
                victim.extinguish();
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.MINING_FATIGUE, 160, 1), owner);
                world.spawnParticles(ParticleTypes.SPLASH, c.x, c.y, c.z, 60, 0.5, 0.5, 0.5, 0.3);
                world.spawnParticles(ParticleTypes.BUBBLE_POP, c.x, c.y, c.z, 20, 0.4, 0.5, 0.4, 0.1);
                sound(world, c, SoundEvents.ENTITY_PLAYER_SPLASH_HIGH_SPEED, 0.9f, 1.0f);
                sound(world, c, SoundEvents.ENTITY_ELDER_GUARDIAN_CURSE, 0.25f, 1.6f);
            }
            case CUSHY -> {
                world.spawnParticles(ParticleTypes.CLOUD, c.x, c.y, c.z, 15, 0.3, 0.4, 0.3, 0.08);
                sound(world, c, SoundEvents.BLOCK_WOOL_BREAK, 1.5f, 0.7f);
                sound(world, c, SoundEvents.ENTITY_PLAYER_ATTACK_KNOCKBACK, 1.0f, 0.9f);
            }
            case BONE -> {
                victim.addStatusEffect(new StatusEffectInstance(StatusEffects.WEAKNESS, 100, 0), owner);
                world.spawnParticles(ParticleTypes.WHITE_ASH, c.x, c.y, c.z, 30, 0.4, 0.5, 0.4, 0.05);
                sound(world, c, SoundEvents.ENTITY_SKELETON_HURT, 1.0f, 0.7f);
                sound(world, c, SoundEvents.BLOCK_BONE_BLOCK_BREAK, 1.2f, 0.8f);
            }
            case PLAIN -> {}
        }
    }

    /** It struck a wall instead of a mob: only the area ones still go off. */
    public void onImpact(ServerWorld world, @Nullable PlayerEntity owner, Vec3d at) {
        switch (this) {
            case EXPLOSIVE -> explode(world, owner, at);
            case RUBBLE -> splash(world, owner, null, at, 3.0, 3.0f, null);
            case GLASS -> splash(world, owner, null, at, 3.0, 4.0f, null);
            case REDSTONE -> {
                splash(world, owner, null, at, 3.0, 4.0f, REDSTONE.ambient);
                world.spawnParticles(REDSTONE.ambient, at.x, at.y, at.z, 40, 1.2, 0.8, 1.2, 0.0);
            }
            case RESONANT -> {
                ring(world, at, 3.5);
                for (LivingEntity e : nearby(world, owner, null, at, 4.0)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.SLOWNESS, 60, 2), owner);
                    hurtExtra(world, owner, e, 3.0f);
                }
                sound(world, at, SoundEvents.BLOCK_AMETHYST_BLOCK_RESONATE, 2.0f, 1.0f);
            }
            case RADIANT -> {
                world.spawnParticles(ParticleTypes.FLASH, at.x, at.y, at.z, 1, 0, 0, 0, 0);
                for (LivingEntity e : nearby(world, owner, null, at, 4.0)) {
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.BLINDNESS, 60, 0), owner);
                    e.addStatusEffect(new StatusEffectInstance(StatusEffects.GLOWING, 100, 0), owner);
                }
            }
            default -> {}
        }
    }

    // ---------------------------------------------------------------------
    // Pieces
    // ---------------------------------------------------------------------

    private static void sound(World world, Vec3d at, net.minecraft.sound.SoundEvent s, float volume, float pitch) {
        world.playSound(null, at.x, at.y, at.z, s, SoundCategory.PLAYERS, volume, pitch * (0.9f + world.random.nextFloat() * 0.2f));
    }

    private static List<LivingEntity> nearby(ServerWorld world, @Nullable PlayerEntity owner, @Nullable LivingEntity except, Vec3d at, double radius) {
        return world.getEntitiesByClass(LivingEntity.class, new net.minecraft.util.math.Box(at, at).expand(radius),
                e -> e != except && BlockTome.canHit(e, owner) && e.squaredDistanceTo(at) <= radius * radius);
    }

    private static void hurtExtra(ServerWorld world, @Nullable PlayerEntity owner, LivingEntity e, float amount) {
        e.timeUntilRegen = 0;
        e.damage(BlockTome.damageSource(world, owner), amount);
    }

    /** Shards or rubble flying out from {@code at}, cutting everything within {@code radius} but the one it hit. */
    private static void splash(ServerWorld world, @Nullable PlayerEntity owner, @Nullable LivingEntity except, Vec3d at,
                               double radius, float amount, @Nullable ParticleEffect extra) {
        for (LivingEntity e : nearby(world, owner, except, at, radius)) {
            hurtExtra(world, owner, e, amount);
            Vec3d c = e.getBoundingBox().getCenter();
            world.spawnParticles(ParticleTypes.CRIT, c.x, c.y, c.z, 6, 0.2, 0.3, 0.2, 0.2);
        }
        world.spawnParticles(ParticleTypes.CRIT, at.x, at.y, at.z, 30, 0.2, 0.2, 0.2, 0.6);
        if (extra != null) world.spawnParticles(extra, at.x, at.y, at.z, 20, 0.6, 0.6, 0.6, 0.0);
    }

    private static void explode(ServerWorld world, @Nullable PlayerEntity owner, Vec3d at) {
        // Hurts and throws mobs (the caster is the explosion's source, so it spares them) but leaves the ground be
        world.createExplosion(owner, BlockTome.damageSource(world, owner), null, at.x, at.y, at.z, 2.2f, false, World.ExplosionSourceType.NONE);
    }

    /** A flat ring of sparkle spreading out at {@code at}. */
    private static void ring(ServerWorld world, Vec3d at, double radius) {
        for (int i = 0; i < 28; i++) {
            double a = i * Math.PI * 2 / 28;
            double x = Math.cos(a), z = Math.sin(a);
            world.spawnParticles(ParticleTypes.END_ROD, at.x + x * 0.4, at.y, at.z + z * 0.4, 0, x, 0.02, z, radius * 0.08);
        }
    }

    /** Copper: an arc of sparks on to each of the three nearest mobs, for a little extra each. */
    private static void arc(ServerWorld world, @Nullable PlayerEntity owner, LivingEntity from) {
        Vec3d a = from.getBoundingBox().getCenter();
        List<LivingEntity> next = nearby(world, owner, from, a, 7.0);
        next.sort((x, y) -> Double.compare(x.squaredDistanceTo(a), y.squaredDistanceTo(a)));
        int n = 0;
        for (LivingEntity e : next) {
            if (n++ >= 3) break;
            Vec3d b = e.getBoundingBox().getCenter();
            int steps = (int) Math.max(4, a.distanceTo(b) * 3);
            for (int i = 0; i <= steps; i++) {
                Vec3d p = a.lerp(b, i / (double) steps);
                double jitter = (i == 0 || i == steps) ? 0 : 0.15;
                world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, p.x + world.random.nextGaussian() * jitter,
                        p.y + world.random.nextGaussian() * jitter, p.z + world.random.nextGaussian() * jitter, 1, 0, 0, 0, 0);
            }
            hurtExtra(world, owner, e, 4.0f);
        }
        world.spawnParticles(ParticleTypes.ELECTRIC_SPARK, a.x, a.y, a.z, 25, 0.3, 0.5, 0.3, 0.3);
        sound(world, a, SoundEvents.BLOCK_COPPER_BULB_TURN_ON, 1.5f, 1.2f);
        sound(world, a, SoundEvents.ENTITY_LIGHTNING_BOLT_IMPACT, 0.35f, 1.6f);
    }

    /** End stone: like eating a chorus fruit, but not by choice. */
    private static void blink(ServerWorld world, LivingEntity e) {
        Vec3d from = e.getPos();
        for (int i = 0; i < 16; i++) {
            double x = e.getX() + (world.random.nextDouble() - 0.5) * 16;
            double y = MathHelper.clamp(e.getY() + (world.random.nextInt(16) - 8), world.getBottomY(), world.getBottomY() + world.getDimension().logicalHeight() - 1);
            double z = e.getZ() + (world.random.nextDouble() - 0.5) * 16;
            if (e.hasVehicle()) e.stopRiding();
            if (e.teleport(x, y, z, true)) {
                world.playSound(null, from.x, from.y, from.z, SoundEvents.ITEM_CHORUS_FRUIT_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                world.playSound(null, e.getX(), e.getY(), e.getZ(), SoundEvents.ENTITY_ENDERMAN_TELEPORT, SoundCategory.PLAYERS, 1.0f, 1.0f);
                return;
            }
        }
    }

    /** Blocks with a trait go into the ring before plain ones. */
    public static boolean special(BlockState state) {
        return of(state) != PLAIN;
    }
}
