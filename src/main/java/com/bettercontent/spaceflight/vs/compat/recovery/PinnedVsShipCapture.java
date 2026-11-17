package com.bettercontent.spaceflight.vs.compat.recovery;

import com.bettercontent.spaceflight.vs.compat.internal.Vs2411MixinGate;
import com.fasterxml.jackson.core.JsonToken;
import com.fasterxml.jackson.databind.ObjectMapper;
import net.minecraft.server.level.ServerLevel;
import org.valkyrienskies.core.impl.game.ships.ShipData;
import org.valkyrienskies.core.impl.game.ships.serialization.shipserver.ServerShipDataConverter;
import org.valkyrienskies.core.impl.game.ships.serialization.shipserver.dto.ServerShipDataV4;
import org.valkyrienskies.core.impl.game.ships.serialization.vspipeline.VSPipelineSerializer;
import org.valkyrienskies.core.impl.program.VSCoreImpl;
import org.valkyrienskies.core.impl.util.serialization.VSJacksonUtil;
import org.valkyrienskies.mod.common.VSGameUtilsKt;
import org.valkyrienskies.mod.common.ValkyrienSkiesMod;

import java.io.IOException;
import java.lang.invoke.MethodHandles;
import java.util.Arrays;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Exact ORIGINAL VS 2.4.11 per-ship persistence converter and SAME configured
 * CBOR mapper that serializePipeline uses. Initial audited baseline: NO live or
 * cached attachment nodes. Nonempty/unknown/transient serializers are refused
 * BEFORE invoking the converter. Converter may perform its normal CA cache /
 * removal-tombstone bookkeeping, not an arbitrary mod serializer call.
 * No native map access, whole-pipeline snapshot, world save or restore.
 */
public final class PinnedVsShipCapture {
    private PinnedVsShipCapture() {}
    public static CaptureEnvelope.Piece capture(ServerLevel level, long shipId) throws IOException {
        if (!level.getServer().isSameThread()) throw new IllegalStateException("Ship capture outside game thread");
        if (!Vs2411MixinGate.verifiedForThisProcess()) throw new IOException("Pinned VS opt-in/artifact gate absent");
        var canonical = VSGameUtilsKt.getShipObjectWorld(level).getAllShips().getById(shipId);
        var loaded = VSGameUtilsKt.getShipObjectWorld(level).getLoadedShips().getById(shipId);
        if (!(canonical instanceof ShipData data) || loaded == null || loaded.getId() != shipId
            || !data.getChunkClaimDimension().equals(VSGameUtilsKt.getDimensionId(level)))
            throw new IOException("Ship not exact loaded canonical game-side source owner");
        var holder = data.getAttachmentHolder();
        if (holder.getAllAttachments().iterator().hasNext())
            throw new IOException("Nonempty live attachment set lacks audited serializer/restore contract");
        try {
            var core = ValkyrienSkiesMod.getVsCore();
            if (!(core instanceof VSCoreImpl actualCore)) throw new IOException("Unpinned VS core implementation");
            // Pinned, typed private-field access, no field-name search or fallback
            // mapper/converter. This may fail under SecureJar modules: refuse.
            var coreLookup = MethodHandles.privateLookupIn(VSCoreImpl.class, MethodHandles.lookup());
            var serializer = (VSPipelineSerializer) coreLookup.findGetter(VSCoreImpl.class,
                "pipelineSerializer", VSPipelineSerializer.class).invoke(actualCore);
            var serializerLookup = MethodHandles.privateLookupIn(VSPipelineSerializer.class, MethodHandles.lookup());
            var converter = (ServerShipDataConverter) serializerLookup.findGetter(VSPipelineSerializer.class,
                "shipDataMapper", ServerShipDataConverter.class).invoke(serializer);
            var mapper = (ObjectMapper) serializerLookup.findGetter(VSPipelineSerializer.class,
                "objectMapper", ObjectMapper.class).invoke(serializer);
            if (mapper != core.getDefaultMapper() || mapper != VSJacksonUtil.INSTANCE.getDefaultMapper())
                throw new IOException("VS per-ship converter not using original configured persistence mapper");
            // One original converter invocation only, after proving no live
            // arbitrary attachment serializer will be called. CA.a() retains
            // upstream cache/tombstone housekeeping under its own lock.
            ServerShipDataV4 dto = converter.convertToDto(data);
            if (dto.getId() != shipId || !dto.getChunkClaimDimension().equals(VSGameUtilsKt.getDimensionId(level))
                || dto.getPersistentAttachedData() == null || !dto.getPersistentAttachedData().isEmpty()
                || dto.getChunkClaim() == null || dto.getTransform() == null || dto.getPrevTickTransform() == null
                || dto.getInertiaData() == null || dto.getVelocity() == null || dto.getOmega() == null)
                throw new IOException("VS codec omitted required metadata or retained unknown attachment nodes");
            // Bounded streaming output: never writeValueAsBytes on unbounded DTO.
            var output = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
            mapper.writerFor(ServerShipDataV4.class).writeValue(output, dto);
            byte[] bytes = output.toByteArray();
            if (bytes.length == 0) throw new IOException("Empty per-ship codec payload");
            // Decode only detached DTO; never instantiate/restore live ShipData
            // or attachment objects in this source-only capture.
            // Jackson 2.14 (pinned inside original VS JAR) predates stream
            // read constraints. Scan bounded CBOR tokens/depth before decode,
            // without changing the shared persistence mapper configuration.
            try (var parser = mapper.getFactory().createParser(bytes)) {
                int depth = 0, tokens = 0;
                JsonToken token;
                while ((token = parser.nextToken()) != null) {
                    if (++tokens > 500_000) throw new IOException("VS DTO token bound exceeded");
                    if (token == JsonToken.START_OBJECT || token == JsonToken.START_ARRAY) {
                        if (++depth > 64) throw new IOException("VS DTO nesting bound exceeded");
                    } else if (token == JsonToken.END_OBJECT || token == JsonToken.END_ARRAY) depth--;
                    if (depth < 0 || parser.hasTextCharacters() && parser.getTextLength() > 1_048_576)
                        throw new IOException("VS DTO malformed/text bound exceeded");
                }
                if (depth != 0) throw new IOException("VS DTO nesting incomplete");
            }
            ServerShipDataV4 decoded = mapper.readValue(bytes, ServerShipDataV4.class);
            if (!sameCompleteV4(dto, decoded))
                throw new IOException("VS per-ship DTO roundtrip lost required metadata fields");
            var roundTrip = new CaptureEnvelope.BoundedBytes(CaptureEnvelope.MAX_PART_BYTES);
            mapper.writerFor(ServerShipDataV4.class).writeValue(roundTrip, decoded);
            if (!Arrays.equals(bytes, roundTrip.toByteArray()))
                throw new IOException("VS per-ship DTO cannot losslessly re-encode");
            return new CaptureEnvelope.Piece(CaptureEnvelope.Kind.VS_SHIP,
                dto.getChunkClaimDimension() + ":" + shipId, bytes);
        } catch (IOException ex) { throw ex; }
        catch (Throwable ex) { throw new IOException("Pinned VS per-ship codec inaccessible/incompatible", ex); }
    }
    /** Compare EVERY V4 field, including previous pose/inertia/chunk set, not only identity. */
    private static boolean sameCompleteV4(ServerShipDataV4 a, ServerShipDataV4 b) {
        return a.getId() == b.getId() && a.isStatic() == b.isStatic()
            && a.getPortalCoolDown() == b.getPortalCoolDown()
            && Objects.equals(a.getName(), b.getName())
            && Objects.equals(a.getChunkClaimDimension(), b.getChunkClaimDimension())
            && a.getChunkClaim().getXIndex() == b.getChunkClaim().getXIndex()
            && a.getChunkClaim().getZIndex() == b.getChunkClaim().getZIndex()
            && Objects.equals(a.getVelocity(), b.getVelocity())
            && Objects.equals(a.getOmega(), b.getOmega())
            && Objects.equals(a.getInertiaData(), b.getInertiaData())
            && Objects.equals(a.getTransform(), b.getTransform())
            && Objects.equals(a.getPrevTickTransform(), b.getPrevTickTransform())
            && Objects.equals(a.getWorldAABB(), b.getWorldAABB())
            && Objects.equals(a.getShipAABB(), b.getShipAABB())
            && Objects.equals(a.getPersistentAttachedData(), b.getPersistentAttachedData())
            && a.getActiveChunks().getSize() == b.getActiveChunks().getSize()
            && activeCoordinates(a).equals(activeCoordinates(b));
    }
    private static Set<String> activeCoordinates(ServerShipDataV4 data) {
        var coordinates = new HashSet<String>();
        data.getActiveChunks().forEach((x, z) -> coordinates.add(x + "," + z));
        return coordinates;
    }
}
