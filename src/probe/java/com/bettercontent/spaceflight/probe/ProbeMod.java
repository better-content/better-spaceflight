package com.bettercontent.spaceflight.probe;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.entity.BlockEntityType;
import net.minecraft.world.level.block.state.BlockBehaviour;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.material.MapColor;
import net.minecraftforge.common.MinecraftForge;
import net.minecraftforge.event.RegisterCommandsEvent;
import net.minecraftforge.eventbus.api.IEventBus;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.event.RegisterGameTestsEvent;
import net.minecraftforge.registries.DeferredRegister;
import net.minecraftforge.registries.ForgeRegistries;
import net.minecraftforge.registries.RegistryObject;

/** Separate nonshipping mod. Never included in stageRuntimeJar. */
@Mod(ProbeMod.ID)
public final class ProbeMod {
    public static final String ID = "better_spaceflight_probe";
    private static final DeferredRegister<net.minecraft.world.level.block.Block> BLOCKS = DeferredRegister.create(ForgeRegistries.BLOCKS, ID);
    private static final DeferredRegister<BlockEntityType<?>> ENTITIES = DeferredRegister.create(ForgeRegistries.BLOCK_ENTITY_TYPES, ID);
    public static final RegistryObject<net.minecraft.world.level.block.Block> CONTROLLER = BLOCKS.register("controller", ProbeBlock::new);
    public static final RegistryObject<net.minecraft.world.level.block.Block> TANK = BLOCKS.register("tank", ProbeBlock::new);
    public static final RegistryObject<BlockEntityType<ProbeEntity>> ENTITY = ENTITIES.register("state",
        () -> BlockEntityType.Builder.of(ProbeEntity::new, CONTROLLER.get(), TANK.get()).build(null));

    public ProbeMod() {
        IEventBus bus = FMLJavaModLoadingContext.get().getModEventBus();
        BLOCKS.register(bus);
        ENTITIES.register(bus);
        bus.addListener((RegisterGameTestsEvent event) -> {
            switch (ProbeGate.parse(System.getProperty("spaceflight.probe.gate", "static"))) {
                case STATIC -> event.register(TransferGameTests.class);
                case DYNAMIC -> event.register(DynamicGameTests.class);
            }
        });
        MinecraftForge.EVENT_BUS.addListener((RegisterCommandsEvent event) -> ProbeCommands.register(event.getDispatcher()));
        MinecraftForge.EVENT_BUS.addListener(ProbeClock::onTick);
    }

    public static final class ProbeBlock extends BaseEntityBlock {
        ProbeBlock() { super(BlockBehaviour.Properties.of().mapColor(MapColor.METAL).strength(3)); }
        @Override public BlockEntity newBlockEntity(BlockPos pos, BlockState state) { return new ProbeEntity(pos, state); }
        @Override public RenderShape getRenderShape(BlockState state) { return RenderShape.MODEL; }
    }

    public static final class ProbeEntity extends BlockEntity {
        private int fuel;
        private String marker = "";
        public ProbeEntity(BlockPos pos, BlockState state) { super(ENTITY.get(), pos, state); }
        public void initialize(String marker, int fuel) { this.marker = marker; this.fuel = fuel; setChanged(); }
        public int fuel() { return fuel; }
        public String marker() { return marker; }
        @Override public void load(CompoundTag tag) {
            super.load(tag); fuel = tag.getInt("ProbeFuel"); marker = tag.getString("ProbeMarker");
        }
        @Override protected void saveAdditional(CompoundTag tag) {
            super.saveAdditional(tag); tag.putInt("ProbeFuel", fuel); tag.putString("ProbeMarker", marker);
        }
    }
}
