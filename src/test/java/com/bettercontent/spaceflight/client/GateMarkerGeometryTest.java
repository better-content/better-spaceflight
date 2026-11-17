package com.bettercontent.spaceflight.client;

import com.bettercontent.spaceflight.travel.ServerTravelPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class GateMarkerGeometryTest {
    @Test void exactFourFixedPolicyDestinations() {
        var markers = GateMarkerGeometry.descriptors();
        assertEquals(4, markers.size());
        assertEquals(Set.of("north-overworld", "east-wastelands", "south-twilight", "west-abyssal"),
            markers.stream().map(GateMarkerGeometry.Marker::id).collect(java.util.stream.Collectors.toSet()));
        for (int i = 0; i < 4; i++) {
            var source = ServerTravelPolicy.GATES.get(i);
            var marker = markers.get(i);
            assertEquals(source.id(), marker.id());
            assertEquals(source.label(), marker.label());
            assertEquals(source.center(), marker.center());
            assertEquals(64.0, marker.radius());
        }
        assertEquals(new ServerTravelPolicy.Position(0, 256, -1024), markers.get(0).center());
        assertEquals(new ServerTravelPolicy.Position(1024, 256, 0), markers.get(1).center());
        assertEquals(new ServerTravelPolicy.Position(0, 256, 1024), markers.get(2).center());
        assertEquals(new ServerTravelPolicy.Position(-1024, 256, 0), markers.get(3).center());
        assertEquals(List.of("Overworld", "Wastelands", "Twilight Forest", "Abyssal"),
            markers.stream().map(GateMarkerGeometry.Marker::label).toList());
    }

    @Test void noServerGrantMeansNoMarkersEvenInsideSpaceGate() {
        assertFalse(GateMarkerGeometry.authoritativeServerGrant());
        for (var marker : GateMarkerGeometry.descriptors()) {
            assertEquals(List.of(), GateMarkerGeometry.visible(ServerTravelPolicy.SPACE, marker.center(),
                GateMarkerGeometry.authoritativeServerGrant()));
        }
        assertEquals(List.of(), GateMarkerGeometry.visible("minecraft:overworld",
            GateMarkerGeometry.descriptors().get(0).center(), true));
        assertEquals(List.of(), GateMarkerGeometry.visible(ServerTravelPolicy.SPACE, null, true));
    }

    @Test void renderingRangeAndGeometryAreBoundedAndSpherical() {
        var marker = GateMarkerGeometry.descriptors().get(0);
        double limit = GateMarkerGeometry.MAX_VIEW_DISTANCE;
        assertEquals(192.0, limit);
        assertEquals(List.of(marker), GateMarkerGeometry.visible(ServerTravelPolicy.SPACE,
            new ServerTravelPolicy.Position(marker.center().x() + limit, marker.center().y(), marker.center().z()), true));
        assertEquals(List.of(), GateMarkerGeometry.visible(ServerTravelPolicy.SPACE,
            new ServerTravelPolicy.Position(marker.center().x() + limit + 0.01,
                marker.center().y(), marker.center().z()), true));
        assertEquals(3 * GateMarkerGeometry.CIRCLE_SEGMENTS, GateMarkerGeometry.outline(marker).size());
        for (var segment : GateMarkerGeometry.outline(marker)) {
            for (var p : List.of(segment.start(), segment.end())) {
                assertEquals(marker.radius() * marker.radius(), p.x() * p.x() + p.y() * p.y() + p.z() * p.z(), 1e-8);
            }
        }
        assertThrows(IllegalArgumentException.class, () -> GateMarkerGeometry.outline(null));
        assertThrows(IllegalArgumentException.class, () -> GateMarkerGeometry.outline(
            new GateMarkerGeometry.Marker("unknown", "Unknown", marker.center(), marker.radius())));
    }
}
