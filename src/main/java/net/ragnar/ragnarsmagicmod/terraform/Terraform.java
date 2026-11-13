package net.ragnar.ragnarsmagicmod.terraform;

import net.fabricmc.fabric.api.event.lifecycle.v1.ServerLifecycleEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.fabricmc.fabric.api.itemgroup.v1.ItemGroupEvents;
import net.fabricmc.fabric.api.networking.v1.PayloadTypeRegistry;
import net.fabricmc.fabric.api.networking.v1.ServerPlayConnectionEvents;
import net.fabricmc.fabric.api.networking.v1.ServerPlayNetworking;
import net.minecraft.block.Block;
import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.entity.Entity;
import net.minecraft.entity.ItemEntity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.item.Item;
import net.minecraft.item.ItemGroups;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NbtCompound;
import net.minecraft.network.RegistryByteBuf;
import net.minecraft.network.codec.PacketCodec;
import net.minecraft.network.codec.PacketCodecs;
import net.minecraft.network.packet.CustomPayload;
import net.minecraft.network.packet.s2c.play.PositionFlag;
import net.minecraft.particle.BlockStateParticleEffect;
import net.minecraft.particle.ParticleTypes;
import net.minecraft.registry.Registries;
import net.minecraft.registry.Registry;
import net.minecraft.registry.RegistryWrapper;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.sound.BlockSoundGroup;
import net.minecraft.sound.SoundCategory;
import net.minecraft.sound.SoundEvents;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Identifier;
import net.minecraft.util.Rarity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec3d;
import net.minecraft.world.PersistentState;
import net.minecraft.world.event.GameEvent;
import net.ragnar.ragnarsmagicmod.RagnarsMagicMod;
import net.ragnar.ragnarsmagicmod.item.ModItems;
import net.ragnar.ragnarsmagicmod.item.custom.TomeItem;
import net.ragnar.ragnarsmagicmod.item.spell.SpellId;
import net.ragnar.ragnarsmagicmod.item.spell.Spells;
import net.ragnar.ragnarsmagicmod.item.spell.TomeTier;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Tome of Terraforming: a master tome for shaping land to build on. Aim at the ground, pick what to do in its menu
 * (Flatten, Smooth, Raise, Lower, Terrace, Cliff, Ramp, Roughen - see TerraformMode), how big and how soft-edged,
 * and cast: the land ripples into its new shape outward from where you aimed. A faint preview of the new ground
 * shows before you cast (TerraformClient), and the last cast can be undone.
 * <p>
 * It moves the land that's there; it never makes land out of nothing. Every block of earth it cuts away goes into
 * your <b>earth reserve</b>, and every block it piles up comes out of it - or, when that's empty, out of the dirt,
 * stone, sand and the like in your inventory. In creative there's no need. Only natural terrain is ever moved
 * (see Terrain): builds, trees, ores and the rest are left exactly where they are.
 * <p>
 * Everything for it lives in this package (the client side in {@code client}).
 */
public final class Terraform {
    private Terraform() {}

    public static final int XP_COST = 2;
    public static final int COOLDOWN = 10;
    /** A request must arrive this recently before the cast to count. */
    private static final int REQUEST_MAX_AGE = 10;
    /** Biggest single reshaping, in blocks changed. */
    public static final int MAX_STEPS = 60_000;
    /** How long the last cast can still be undone. */
    public static final int UNDO_TICKS = 20 * 60 * 5;
    public static final int MAX_RESERVE = 1_000_000;

    public static final TomeItem TOME_OF_TERRAFORMING = (TomeItem) Registry.register(
            Registries.ITEM,
            Identifier.of(RagnarsMagicMod.MOD_ID, "tome_of_terraforming"),
            new TomeItem(new Item.Settings().maxCount(1).rarity(Rarity.EPIC), // Master
                    TomeTier.MASTER, SpellId.TERRAFORMING, XP_COST
            ).setCooldown(COOLDOWN)
    );

    // ---------------------------------------------------------------------
    // Network
    // ---------------------------------------------------------------------

    /** Client -> server, just before the cast: the aimed block and the settings. The server works everything out again. */
    public record Request(BlockPos anchor, NbtCompound settings) implements CustomPayload {
        public static final Id<Request> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "terraform_request"));
        public static final PacketCodec<RegistryByteBuf, Request> CODEC = PacketCodec.tuple(
                BlockPos.PACKET_CODEC, Request::anchor, PacketCodecs.NBT_COMPOUND, Request::settings, Request::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Client -> server: undo my last terraforming. */
    public record Undo() implements CustomPayload {
        public static final Id<Undo> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "terraform_undo"));
        public static final PacketCodec<RegistryByteBuf, Undo> CODEC = PacketCodec.unit(new Undo());

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    /** Server -> client: your earth reserve now holds this much. */
    public record Earth(int amount) implements CustomPayload {
        public static final Id<Earth> ID = new Id<>(Identifier.of(RagnarsMagicMod.MOD_ID, "terraform_earth"));
        public static final PacketCodec<RegistryByteBuf, Earth> CODEC = PacketCodec.tuple(PacketCodecs.VAR_INT, Earth::amount, Earth::new);

        @Override
        public Id<? extends CustomPayload> getId() {
            return ID;
        }
    }

    // ---------------------------------------------------------------------
    // Jobs: a reshaping (or an undo) applied a block at a time, rippling outward
    // ---------------------------------------------------------------------

    /**
     * One block's change: from {@code from} to {@code to}, at {@code delay} ticks. {@code earth} is what it does to
     * the reserve (+1 for earth cut away, -1 for earth piled up). A {@code replant} only goes ahead if the plant can
     * stand there; any other step only if the block is still exactly {@code from}.
     */
    record Step(BlockPos pos, BlockState from, BlockState to, int earth, int delay, boolean replant) {}

    private static final class Job {
        final ServerPlayerEntity player;
        final ServerWorld world;
        final List<Step> steps;
        final List<Step> applied = new ArrayList<>();
        final boolean creative, undo;
        final Box area;
        /** Earth the job may still spend on piling up (survival). */
        int credit;
        int earthChange;
        int next, age;

        Job(ServerPlayerEntity player, ServerWorld world, List<Step> steps, boolean undo, Box area) {
            this.player = player;
            this.world = world;
            this.steps = steps;
            this.undo = undo;
            this.creative = player.getAbilities().creativeMode;
            this.area = area;
        }
    }

    private record Pending(BlockPos anchor, TerraformSettings settings, int tick) {}

    private record LastCast(ServerWorld world, List<Step> applied, int tick) {}

    private static final Map<UUID, Pending> PENDING = new HashMap<>();
    private static final Map<UUID, Job> JOBS = new HashMap<>();
    private static final Map<UUID, LastCast> LAST = new HashMap<>();

    public static void register() {
        ModItems.TOMES.computeIfAbsent(SpellId.TERRAFORMING, k -> new EnumMap<>(TomeTier.class)).put(TomeTier.MASTER, TOME_OF_TERRAFORMING);
        Spells.register(SpellId.TERRAFORMING, new TerraformSpell());
        ItemGroupEvents.modifyEntriesEvent(ItemGroups.TOOLS).register(entries -> entries.addAfter(ModItems.TOME_OF_BUILDING, TOME_OF_TERRAFORMING));

        PayloadTypeRegistry.playC2S().register(Request.ID, Request.CODEC);
        PayloadTypeRegistry.playC2S().register(Undo.ID, Undo.CODEC);
        PayloadTypeRegistry.playS2C().register(Earth.ID, Earth.CODEC);
        ServerPlayNetworking.registerGlobalReceiver(Request.ID, (payload, context) -> {
            ServerPlayerEntity p = context.player();
            PENDING.put(p.getUuid(), new Pending(payload.anchor(), TerraformSettings.fromNbt(payload.settings()), p.getServer().getTicks()));
        });
        ServerPlayNetworking.registerGlobalReceiver(Undo.ID, (payload, context) -> undo(context.player()));

        ServerPlayConnectionEvents.JOIN.register((handler, sender, server) -> sendEarth(handler.player));
        ServerPlayConnectionEvents.DISCONNECT.register((handler, server) -> {
            UUID id = handler.player.getUuid();
            PENDING.remove(id);
            Job job = JOBS.remove(id);
            if (job != null) finish(job);
            LAST.remove(id);
        });
        ServerTickEvents.END_SERVER_TICK.register(Terraform::tick);
        ServerLifecycleEvents.SERVER_STOPPING.register(server -> {
            for (Job job : JOBS.values()) finish(job);
            JOBS.clear();
            PENDING.clear();
            LAST.clear();
        });
    }

    /** The cast, on the server: picks up the request the client sent with it. */
    static boolean cast(ServerWorld world, ServerPlayerEntity player) {
        Pending pending = PENDING.remove(player.getUuid());
        if (pending == null || world.getServer().getTicks() - pending.tick() > REQUEST_MAX_AGE) {
            player.sendMessage(Text.literal("Aim at the ground to terraform (up to " + (int) TerraformPlanner.REACH + " blocks away).")
                    .formatted(Formatting.GRAY), true);
            return false;
        }
        return start(world, player, pending.anchor(), pending.settings());
    }

    /** Checks and starts a reshaping. Returns true if it started. Public so the game tests can drive it directly. */
    public static boolean start(ServerWorld world, ServerPlayerEntity player, BlockPos anchor, TerraformSettings settings) {
        if (!player.getAbilities().allowModifyWorld) {
            player.sendMessage(Text.literal("You can't change the land here.").formatted(Formatting.RED), true);
            return false;
        }
        if (JOBS.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("Still shaping the land...").formatted(Formatting.GRAY), true);
            return false;
        }
        double reach = TerraformPlanner.REACH + 8;
        if (player.getEyePos().squaredDistanceTo(Vec3d.ofCenter(anchor)) > reach * reach) {
            player.sendMessage(Text.literal("Too far away.").formatted(Formatting.RED), true);
            return false;
        }
        settings.clamp();
        TerraformPlanner.Target target = TerraformPlanner.targetAt(world, anchor);
        if (target == null) {
            player.sendMessage(Text.literal("There's no ground there to shape.").formatted(Formatting.GRAY), true);
            return false;
        }
        TerraformPlanner.Plan plan = TerraformPlanner.plan(world, player, settings, target);
        List<Step> steps = steps(world, player, plan);
        if (steps.isEmpty()) {
            player.sendMessage(Text.literal("The land here is already like that.").formatted(Formatting.GRAY), true);
            return false;
        }
        if (steps.size() > MAX_STEPS) {
            player.sendMessage(Text.literal("That's too much land at once: make it smaller.").formatted(Formatting.RED), true);
            return false;
        }

        Job job = new Job(player, world, steps, false, area(plan));
        if (!job.creative && !budget(job, steps)) return false;
        JOBS.put(player.getUuid(), job);
        LAST.remove(player.getUuid());

        BlockPos c = target.anchor();
        world.playSound(null, c, SoundEvents.BLOCK_ROOTED_DIRT_BREAK, SoundCategory.BLOCKS, 1.2f, 0.6f);
        world.playSound(null, c, SoundEvents.BLOCK_GRAVEL_HIT, SoundCategory.BLOCKS, 1.0f, 0.5f);
        world.playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME, SoundCategory.PLAYERS, 0.6f, 0.7f);
        return true;
    }

    /** Turns a plan into block changes, in ripple order, checking each spot may be changed by this player. */
    static List<Step> steps(ServerWorld world, PlayerEntity player, TerraformPlanner.Plan plan) {
        List<Step> steps = new ArrayList<>();
        BlockState air = Blocks.AIR.getDefaultState(), water = Blocks.WATER.getDefaultState();
        for (TerraformPlanner.Column col : plan.columns()) {
            if (!col.changed()) continue;
            int x = col.x(), z = col.z(), h = col.oldTop(), n = col.newTop(), d = col.delay();
            BlockPos topPos = new BlockPos(x, h, z);
            if (!world.isChunkLoaded(topPos) || !world.canPlayerModifyAt(player, topPos)) continue;

            // What's lying on the ground comes off first (and goes back on top after, if it's a small thing)
            BlockState deco = world.getBlockState(topPos.up());
            BlockState carried = null;
            if (Terrain.isDecoration(deco)) {
                if (Terrain.isTall(deco)) {
                    BlockState upper = world.getBlockState(topPos.up(2));
                    if (Terrain.isTall(upper)) steps.add(new Step(topPos.up(2), upper, air, 0, d, false));
                } else {
                    carried = deco;
                }
                steps.add(new Step(topPos.up(), deco, col.wet() ? water : air, 0, d, false));
            }

            if (n < h) {
                // Cut down, top first: what was cut fills with water if water stood over it
                for (int y = h; y > n; y--) {
                    BlockPos p = new BlockPos(x, y, z);
                    steps.add(new Step(p, world.getBlockState(p), col.wet() ? water : air, 1, d, false));
                }
                // A cut through grassland grows its grass back on the new ground
                BlockPos floor = new BlockPos(x, n, z);
                BlockState now = world.getBlockState(floor);
                if (Terrain.isCover(col.top()) && !Terrain.isCover(now) && Terrain.isTerrain(now)) {
                    steps.add(new Step(floor, now, col.top(), 0, d, false));
                }
            } else {
                // Built up, from the bottom: the old top is buried (grass turns to dirt), then soil, then the top
                if (Terrain.isCover(col.top())) steps.add(new Step(topPos, world.getBlockState(topPos), Terrain.buried(col.top()), 0, d, false));
                for (int y = h + 1; y <= n; y++) {
                    BlockPos p = new BlockPos(x, y, z);
                    BlockState was = y == h + 1 && Terrain.isDecoration(deco) ? (col.wet() ? water : air) : world.getBlockState(p);
                    if (Terrain.isTall(was)) was = air;
                    BlockState block = y == n ? col.top() : y > n - 4 ? col.sub() : col.deep();
                    steps.add(new Step(p, was, block, -1, d, false));
                }
            }
            if (carried != null) steps.add(new Step(new BlockPos(x, n + 1, z), col.wet() ? water : air, carried, 0, d, true));
        }
        return steps;
    }

    /** The space a plan reaches over, for finding what's standing on it. */
    private static Box area(TerraformPlanner.Plan plan) {
        int minX = Integer.MAX_VALUE, minZ = Integer.MAX_VALUE, maxX = Integer.MIN_VALUE, maxZ = Integer.MIN_VALUE;
        int minY = Integer.MAX_VALUE, maxY = Integer.MIN_VALUE;
        for (TerraformPlanner.Column c : plan.columns()) {
            minX = Math.min(minX, c.x());
            maxX = Math.max(maxX, c.x());
            minZ = Math.min(minZ, c.z());
            maxZ = Math.max(maxZ, c.z());
            minY = Math.min(minY, Math.min(c.oldTop(), c.newTop()));
            maxY = Math.max(maxY, Math.max(c.oldTop(), c.newTop()));
        }
        return new Box(minX, minY - 2, minZ, maxX + 1, maxY + 4, maxZ + 1);
    }

    /**
     * Survival: makes sure there's earth enough for what the job piles up beyond what it cuts away, topping the reserve
     * up from the inventory if needed. Says how much is missing (and returns false) if there isn't.
     */
    private static boolean budget(Job job, List<Step> steps) {
        int cut = 0, fill = 0;
        for (Step s : steps) {
            if (s.earth() > 0) cut += s.earth();
            else fill -= s.earth();
        }
        int reserve = reserve(job.player);
        int short_ = fill - cut - reserve;
        if (short_ > 0) {
            int have = countEarthItems(job.player);
            if (have < short_) {
                job.player.sendMessage(Text.literal("Not enough earth: needs " + short_ + " more (your reserve has " + reserve
                        + "; dirt, stone, sand and the like in your inventory count too).").formatted(Formatting.RED), true);
                return false;
            }
            takeEarthItems(job.player, short_);
            setReserve(job.player, reserve + short_);
            reserve += short_;
        }
        job.credit = reserve + cut;
        return true;
    }

    private static void tick(MinecraftServer server) {
        if (JOBS.isEmpty()) return;
        Iterator<Map.Entry<UUID, Job>> it = JOBS.entrySet().iterator();
        while (it.hasNext()) {
            Job job = it.next().getValue();
            if (job.player.isRemoved() || job.player.getServerWorld() != job.world) {
                finish(job);
                it.remove();
                continue;
            }
            job.age++;
            Map<Long, Integer> raised = new HashMap<>();
            List<Step> shown = new ArrayList<>();
            int cutThisTick = 0, fillThisTick = 0;
            while (job.next < job.steps.size() && job.steps.get(job.next).delay() <= job.age) {
                Step s = job.steps.get(job.next++);
                if (!apply(job, s)) continue;
                if (s.earth() > 0) cutThisTick++;
                if (s.earth() < 0) {
                    fillThisTick++;
                    raised.merge(TerraformPlanner.key(s.pos().getX(), s.pos().getZ()), s.pos().getY(), Math::max);
                }
                if (shown.size() < 14 && job.world.getRandom().nextInt(3) == 0) shown.add(s);
            }
            effects(job, shown, cutThisTick, fillThisTick);
            if (!raised.isEmpty()) lift(job, raised);
            if (job.next >= job.steps.size()) {
                finish(job);
                it.remove();
            }
        }
    }

    private static boolean apply(Job job, Step s) {
        ServerWorld w = job.world;
        BlockState now = w.getBlockState(s.pos());
        if (s.replant()) {
            if (!Terrain.isOpen(now) || !s.to().canPlaceAt(w, s.pos())) return false;
        } else if (now != s.from()) {
            return false; // something changed here since: leave it be
        }
        if (s.earth() < 0 && !job.creative) {
            if (job.credit <= 0) return false;
            job.credit--;
        }
        w.setBlockState(s.pos(), s.to(), Block.NOTIFY_ALL);
        if (s.earth() != 0) {
            w.emitGameEvent(s.earth() > 0 ? GameEvent.BLOCK_DESTROY : GameEvent.BLOCK_PLACE, s.pos(), GameEvent.Emitter.of(job.player, s.to()));
        }
        job.earthChange += s.earth();
        job.applied.add(s);
        return true;
    }

    /** A few puffs of the earth moving, and one sound a tick for whichever way most of it went. */
    private static void effects(Job job, List<Step> shown, int cut, int fill) {
        ServerWorld w = job.world;
        for (Step s : shown) {
            BlockState state = s.earth() < 0 ? s.to() : s.from();
            if (state.isAir() || !state.getFluidState().isEmpty()) continue;
            Vec3d c = Vec3d.ofCenter(s.pos());
            w.spawnParticles(new BlockStateParticleEffect(ParticleTypes.BLOCK, state), c.x, c.y + 0.5, c.z, 3, 0.3, 0.2, 0.3, 0.05);
        }
        if (cut + fill == 0 || shown.isEmpty()) return;
        Step s = shown.get(0);
        BlockState state = s.earth() < 0 ? s.to() : s.from();
        if (state.isAir()) return;
        BlockSoundGroup g = state.getSoundGroup();
        float progress = job.next / (float) job.steps.size();
        w.playSound(null, s.pos(), fill >= cut ? g.getPlaceSound() : g.getBreakSound(), SoundCategory.BLOCKS,
                0.9f, g.getPitch() * (0.75f + progress * 0.3f));
    }

    /** Nothing standing on ground that rises is left inside it: it's carried up on top. */
    private static void lift(Job job, Map<Long, Integer> raised) {
        for (Entity e : job.world.getEntitiesByClass(Entity.class, job.area,
                en -> (en instanceof LivingEntity || en instanceof ItemEntity) && !en.isSpectator())) {
            Integer top = raised.get(TerraformPlanner.key(e.getBlockX(), e.getBlockZ()));
            if (top == null || e.getY() >= top + 1 || e.getY() < top - 6) continue;
            if (e instanceof ServerPlayerEntity p) {
                p.networkHandler.requestTeleport(p.getX(), top + 1, p.getZ(), p.getYaw(), p.getPitch(), EnumSet.noneOf(PositionFlag.class));
            } else {
                e.setPosition(e.getX(), top + 1, e.getZ());
            }
            e.fallDistance = 0;
        }
    }

    private static void finish(Job job) {
        if (!job.creative && job.earthChange != 0) {
            setReserve(job.player, reserve(job.player) + job.earthChange);
        }
        if (!job.undo && !job.applied.isEmpty()) {
            LAST.put(job.player.getUuid(), new LastCast(job.world, job.applied, job.world.getServer().getTicks()));
        }
    }

    // ---------------------------------------------------------------------
    // Undo
    // ---------------------------------------------------------------------

    /** Puts back what the player's last terraforming changed (wherever it hasn't been changed again since). */
    public static boolean undo(ServerPlayerEntity player) {
        if (JOBS.containsKey(player.getUuid())) {
            player.sendMessage(Text.literal("Still shaping the land...").formatted(Formatting.GRAY), true);
            return false;
        }
        LastCast last = LAST.get(player.getUuid());
        if (last == null || last.world() != player.getServerWorld()
                || player.getServer().getTicks() - last.tick() > UNDO_TICKS) {
            player.sendMessage(Text.literal("Nothing to undo.").formatted(Formatting.GRAY), true);
            return false;
        }
        List<Step> back = new ArrayList<>(last.applied().size());
        int n = last.applied().size();
        for (int i = n - 1; i >= 0; i--) {
            Step s = last.applied().get(i);
            if (s.replant()) {
                // A replanted plant comes off again (it was carried, not made)
                back.add(new Step(s.pos(), s.to(), s.from(), 0, 0, false));
                continue;
            }
            int delay = (int) ((long) (n - 1 - i) * TerraformPlanner.SPREAD / Math.max(1, n));
            back.add(new Step(s.pos(), s.to(), s.from(), -s.earth(), delay, false));
        }
        Job job = new Job(player, last.world(), back, true, Box.of(player.getPos(), 1, 1, 1));
        if (!job.creative && !budget(job, back)) return false;
        LAST.remove(player.getUuid());
        JOBS.put(player.getUuid(), job);
        player.sendMessage(Text.literal("Undoing your last terraforming.").formatted(Formatting.GRAY), true);
        player.getServerWorld().playSound(null, player.getX(), player.getY(), player.getZ(), SoundEvents.BLOCK_AMETHYST_BLOCK_CHIME,
                SoundCategory.PLAYERS, 0.6f, 0.5f);
        return true;
    }

    /** For tests: whether a reshaping is still under way for this player. */
    public static boolean isBusy(PlayerEntity player) {
        return JOBS.containsKey(player.getUuid());
    }

    // ---------------------------------------------------------------------
    // The earth reserve
    // ---------------------------------------------------------------------

    public static int reserve(ServerPlayerEntity player) {
        return state(player.getServer()).earth.getOrDefault(player.getUuid(), 0);
    }

    public static void setReserve(ServerPlayerEntity player, int amount) {
        State s = state(player.getServer());
        int clamped = Math.max(0, Math.min(MAX_RESERVE, amount));
        if (clamped == 0) s.earth.remove(player.getUuid());
        else s.earth.put(player.getUuid(), clamped);
        s.markDirty();
        sendEarth(player);
    }

    private static void sendEarth(ServerPlayerEntity player) {
        if (player.getServer() != null && ServerPlayNetworking.canSend(player, Earth.ID)) {
            ServerPlayNetworking.send(player, new Earth(reserve(player)));
        }
    }

    private static int countEarthItems(PlayerEntity player) {
        int n = 0;
        for (ItemStack stack : allStacks(player)) if (!stack.isEmpty() && Terrain.isEarthItem(stack.getItem())) n += stack.getCount();
        return n;
    }

    private static void takeEarthItems(PlayerEntity player, int amount) {
        for (ItemStack stack : allStacks(player)) {
            if (amount <= 0) return;
            if (stack.isEmpty() || !Terrain.isEarthItem(stack.getItem())) continue;
            int take = Math.min(amount, stack.getCount());
            stack.decrement(take);
            amount -= take;
        }
    }

    private static List<ItemStack> allStacks(PlayerEntity player) {
        List<ItemStack> stacks = new ArrayList<>(player.getInventory().main);
        stacks.addAll(player.getInventory().offHand);
        return stacks;
    }

    private static State state(MinecraftServer server) {
        return server.getOverworld().getPersistentStateManager().getOrCreate(TYPE, "ragnarsmagicmod_earth");
    }

    private static final PersistentState.Type<State> TYPE = new PersistentState.Type<>(State::new, State::fromNbt, null);

    public static final class State extends PersistentState {
        final Map<UUID, Integer> earth = new HashMap<>();

        @Override
        public NbtCompound writeNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            NbtCompound players = new NbtCompound();
            earth.forEach((id, amount) -> players.putInt(id.toString(), amount));
            nbt.put("players", players);
            return nbt;
        }

        static State fromNbt(NbtCompound nbt, RegistryWrapper.WrapperLookup lookup) {
            State s = new State();
            NbtCompound players = nbt.getCompound("players");
            for (String key : players.getKeys()) {
                try {
                    s.earth.put(UUID.fromString(key), players.getInt(key));
                } catch (IllegalArgumentException ignored) {
                    // not a player id
                }
            }
            return s;
        }
    }
}
