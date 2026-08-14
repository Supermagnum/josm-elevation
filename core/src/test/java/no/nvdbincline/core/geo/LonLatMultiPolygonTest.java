package no.nvdbincline.core.geo;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class LonLatMultiPolygonTest {
    @Test
    void squareContainsInteriorAndRejectsExterior() {
        LonLatMultiPolygon poly =
                new LonLatMultiPolygon(
                        List.of(
                                new LonLatMultiPolygon.Polygon(
                                        List.of(
                                                new LonLatMultiPolygon.Ring(
                                                        List.of(
                                                                new double[] {10, 60},
                                                                new double[] {11, 60},
                                                                new double[] {11, 61},
                                                                new double[] {10, 61},
                                                                new double[] {10, 60}))))));
        assertTrue(poly.contains(10.5, 60.5));
        assertFalse(poly.contains(9.5, 60.5));
        assertFalse(poly.contains(10.5, 61.5));
    }
}
