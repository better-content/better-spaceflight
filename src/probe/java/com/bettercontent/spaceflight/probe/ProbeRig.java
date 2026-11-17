package com.bettercontent.spaceflight.probe;

import com.bettercontent.spaceflight.vs.Vs2411Adapter;
import net.minecraft.core.BlockPos;
import net.minecraft.core.registries.Registries;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.nbt.NbtUtils;
import net.minecraft.nbt.Tag;
import net.minecraft.resources.ResourceKey;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.entity.ChestBlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.saveddata.SavedData;
import org.joml.Vector3d;
import org.valkyrienskies.core.api.ships.ServerShip;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

/** Exact-state diagnostic fixture; not a production ship serializer or transfer journal. */
public final class ProbeRig {
    public static final ResourceKey<Level> SPACE = ResourceKey.create(Registries.DIMENSION,
        new ResourceLocation("better_spaceflight", "space"));
    private static final int SCHEMA = 1;
    public final long shipId;
    public final BlockPos seat;
    public final String marker;
    public final Map<BlockPos, BlockState> states;
    public final Map<BlockPos, CompoundTag> entities;
    private ResourceKey<Level> dimension;
    public final ResourceKey<Level> home;
    public final Vector3d homePosition;
    public int hops;

    private ProbeRig(long shipId, BlockPos seat, String marker, Map<BlockPos, BlockState> states,
                     Map<BlockPos, CompoundTag> entities, ResourceKey<Level> dimension,
                     ResourceKey<Level> home, Vector3d homePosition, int hops) {
        this.shipId = shipId; this.seat = seat; this.marker = marker;
        this.states = Map.copyOf(states); this.entities = Map.copyOf(entities);
        this.dimension = dimension; this.home = home; this.homePosition = homePosition; this.hops = hops;
    }

    public ResourceKey<Level> dimension() { return dimension; }
    public ServerLevel level(MinecraftServer server) {
        ServerLevel level = server.getLevel(dimension);
        if (level == null) throw new IllegalStateException("Missing recorded dimension " + dimension.location());
        return level;
    }
    public ServerShip ship(MinecraftServer server) {
        ServerShip ship = Vs2411Adapter.find(level(server), shipId);
        if (ship == null) throw new IllegalStateException("Missing recorded ship " + shipId);
        return ship;
    }

    public static ProbeRig create(ServerLevel level, BlockPos origin) {
        Map<BlockPos, BlockState> blocks = new LinkedHashMap<>();
        for (int x = 0; x < 3; x++) for (int z = 0; z < 3; z++) {
            blocks.put(origin.offset(x, 0, z), Blocks.IRON_BLOCK.defaultBlockState());
        }
        BlockPos controller = origin.offset(1, 1, 1);
        BlockPos tank = origin.offset(2, 1, 1);
        BlockPos chest = origin.offset(0, 1, 1);
        blocks.put(controller, ProbeMod.CONTROLLER.get().defaultBlockState());
        blocks.put(tank, ProbeMod.TANK.get().defaultBlockState());
        blocks.put(chest, Blocks.CHEST.defaultBlockState());
        for (BlockPos pos : blocks.keySet()) {
            if (!level.isInWorldBounds(pos) || !level.getBlockState(pos).isAir()) {
                throw new IllegalStateException("Probe refuses nonempty or out-of-bounds position " + pos);
            }
        }
        blocks.forEach((pos, state) -> level.setBlock(pos, state, 3));
        String marker = UUID.randomUUID().toString();
        ((ProbeMod.ProbeEntity) level.getBlockEntity(controller)).initialize(marker, 42);
        ((ProbeMod.ProbeEntity) level.getBlockEntity(tank)).initialize(marker, 137);
        ItemStack cargo = new ItemStack(Items.DIAMOND, 7);
        cargo.getOrCreateTag().putString("ProbeMarker", marker);
        ((ChestBlockEntity) level.getBlockEntity(chest)).setItem(0, cargo);

        Vs2411Adapter.Assembly assembled = Vs2411Adapter.assembleProbe(level, blocks.keySet());
        assembled.ship().setStatic(true); // Explicit safe test fixture, never advertised as flight.
        Map<BlockPos, BlockState> expectedStates = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> expectedEntities = new LinkedHashMap<>();
        blocks.forEach((pos, state) -> {
            BlockPos local = assembled.relocated(pos);
            if (!level.getBlockState(pos).isAir()) throw new IllegalStateException("Assembly left original block " + pos);
            if (!level.getBlockState(local).equals(state)) throw new IllegalStateException("Assembly changed state " + local);
            expectedStates.put(local, state);
            var entity = level.getBlockEntity(local);
            if (entity != null) expectedEntities.put(local, entity.saveWithFullMetadata());
        });
        if (expectedEntities.size() != 3) throw new IllegalStateException("Expected controller, tank and chest block entities");
        ProbeRig rig = new ProbeRig(assembled.ship().getId(), assembled.relocated(controller).above(), marker,
            expectedStates, expectedEntities, level.dimension(), level.dimension(),
            new Vector3d(assembled.ship().getTransform().getPositionInWorld()), 0);
        rig.assertState(level.getServer());
        return rig;
    }

    public void assertState(MinecraftServer server) {
        ServerLevel level = level(server);
        ServerShip ship = ship(server);
        if (!ship.getChunkClaimDimension().equals(Vs2411Adapter.dimension(level))) {
            throw new IllegalStateException("Ship dimension differs from recorded ownership: " + ship.getChunkClaimDimension());
        }
        states.forEach((pos, state) -> {
            if (!level.getBlockState(pos).equals(state)) throw new IllegalStateException("Missing/changed block " + pos);
        });
        entities.forEach((pos, nbt) -> {
            var entity = level.getBlockEntity(pos);
            if (entity == null || !entity.saveWithFullMetadata().equals(nbt)) {
                throw new IllegalStateException("Block entity/cargo/fuel mismatch at " + pos + "; expected " + nbt
                    + "; actual " + (entity == null ? "missing" : entity.saveWithFullMetadata()));
            }
        });
    }

    public void hop(ServerLevel destination, Vector3d position) {
        MinecraftServer server = destination.getServer();
        ServerLevel source = level(server);
        if (source == destination) throw new IllegalArgumentException("Round-trip probe requires another dimension");
        assertState(server);
        Vs2411Adapter.teleportForProbe(source, destination, ship(server), position);
        // Native API is synchronous for chunk copy; do not conceal partial failure by rebuilding.
        dimension = destination.dimension();
        assertState(server);
        for (BlockPos pos : states.keySet()) {
            if (!source.getBlockState(pos).isAir() || source.getBlockEntity(pos) != null) {
                throw new IllegalStateException("Native transfer duplicated source block/entity at " + pos);
            }
        }
        hops++;
    }

    public CompoundTag save() {
        CompoundTag tag = new CompoundTag();
        tag.putInt("Schema", SCHEMA); tag.putLong("Ship", shipId); tag.putLong("Seat", seat.asLong());
        tag.putString("Marker", marker); tag.putString("Dimension", dimension.location().toString());
        tag.putString("Home", home.location().toString()); tag.putDouble("HomeX", homePosition.x);
        tag.putDouble("HomeY", homePosition.y); tag.putDouble("HomeZ", homePosition.z); tag.putInt("Hops", hops);
        ListTag blocks = new ListTag();
        states.forEach((pos, state) -> {
            CompoundTag block = new CompoundTag(); block.putLong("Pos", pos.asLong());
            block.put("State", NbtUtils.writeBlockState(state));
            if (entities.containsKey(pos)) block.put("Entity", entities.get(pos).copy());
            blocks.add(block);
        });
        tag.put("Blocks", blocks); return tag;
    }

    public static ProbeRig load(CompoundTag tag, ServerLevel registryLevel) {
        if (tag.getInt("Schema") != SCHEMA) throw new IllegalStateException("Unsupported probe schema");
        Map<BlockPos, BlockState> states = new LinkedHashMap<>();
        Map<BlockPos, CompoundTag> entities = new LinkedHashMap<>();
        for (Tag raw : tag.getList("Blocks", Tag.TAG_COMPOUND)) {
            CompoundTag block = (CompoundTag) raw; BlockPos pos = BlockPos.of(block.getLong("Pos"));
            states.put(pos, NbtUtils.readBlockState(registryLevel.holderLookup(Registries.BLOCK), block.getCompound("State")));
            if (block.contains("Entity", Tag.TAG_COMPOUND)) entities.put(pos, block.getCompound("Entity").copy());
        }
        return new ProbeRig(tag.getLong("Ship"), BlockPos.of(tag.getLong("Seat")), tag.getString("Marker"), states, entities,
            key(tag.getString("Dimension")), key(tag.getString("Home")),
            new Vector3d(tag.getDouble("HomeX"), tag.getDouble("HomeY"), tag.getDouble("HomeZ")), tag.getInt("Hops"));
    }

    private static ResourceKey<Level> key(String key) {
        return ResourceKey.create(Registries.DIMENSION, new ResourceLocation(key));
    }

    public static final class Record extends SavedData {
        public ProbeRig rig;
        public static Record get(MinecraftServer server) {
            ServerLevel home = server.overworld();
            return home.getDataStorage().computeIfAbsent(tag -> {
                Record record = new Record();
                if (tag.contains("Rig", Tag.TAG_COMPOUND)) record.rig = ProbeRig.load(tag.getCompound("Rig"), home);
                return record;
            }, Record::new, "better_spaceflight_probe");
        }
        @Override public CompoundTag save(CompoundTag tag) {
            if (rig != null) tag.put("Rig", rig.save());
            return tag;
        }
    }
}
