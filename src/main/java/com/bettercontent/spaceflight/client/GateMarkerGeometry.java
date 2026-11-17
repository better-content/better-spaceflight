package com.bettercontent.spaceflight.client;

import com.bettercontent.spaceflight.travel.ServerTravelPolicy;

import java.util.ArrayList;
import java.util.List;

/** Pure, bounded presentation geometry for the four existing policy regions. No world objects. */
public final class GateMarkerGeometry {
    public static final double MAX_VIEW_DISTANCE = 192.0;
    public static final int CIRCLE_SEGMENTS = 16;
    private static final List<Marker> MARKERS = ServerTravelPolicy.GATES.stream()
        .map(region -> new Marker(region.id(), region.label(), region.center(), region.radius()))
        .toList();

    public record Marker(String id, String label, ServerTravelPolicy.Position center, double radius) {}
    public record Point(double x, double y, double z) {}
    public record Segment(Point start, Point end) {}

    private GateMarkerGeometry() {}

    /** A descriptor is not an offer, activation boundary, or proof of server readiness. */
    public static List<Marker> descriptors() { return MARKERS; }

    /** No authoritative server presentation grant exists in this source-only batch. */
    public static boolean authoritativeServerGrant() { return false; }

    /** All normal play returns empty, including when the camera is inside a region. */
    public static List<Marker> visible(String dimension, ServerTravelPolicy.Position camera,
                                       boolean authoritativeServerGrant) {
        if (!authoritativeServerGrant || !ServerTravelPolicy.SPACE.equals(dimension) || camera == null)
            return List.of();
        var visible = new ArrayList<Marker>(MARKERS.size());
        for (var marker : MARKERS) {
            double dx = marker.center.x() - camera.x();
            double dy = marker.center.y() - camera.y();
            double dz = marker.center.z() - camera.z();
            double distanceSquared = dx * dx + dy * dy + dz * dz;
            if (Double.isFinite(distanceSquared) && distanceSquared <= MAX_VIEW_DISTANCE * MAX_VIEW_DISTANCE)
                visible.add(marker);
        }
        return List.copyOf(visible);
    }

    /** Three great circles, not a misleading square activation region; at most 48 edges per gate. */
    public static List<Segment> outline(Marker marker) {
        if (marker == null || !MARKERS.contains(marker)) throw new IllegalArgumentException("Unknown gate marker");
        var segments = new ArrayList<Segment>(3 * CIRCLE_SEGMENTS);
        for (int plane = 0; plane < 3; plane++) for (int i = 0; i < CIRCLE_SEGMENTS; i++) {
            segments.add(new Segment(point(marker.radius, plane, i), point(marker.radius, plane, i + 1)));
        }
        return List.copyOf(segments);
    }

    private static Point point(double radius, int plane, int index) {
        double angle = 2 * Math.PI * index / CIRCLE_SEGMENTS;
        double a = radius * Math.cos(angle), b = radius * Math.sin(angle);
        return switch (plane) {
            case 0 -> new Point(a, 0, b);
            case 1 -> new Point(a, b, 0);
            case 2 -> new Point(0, a, b);
            default -> throw new AssertionError("only three planes");
        };
    }
}
