package no.nvdbincline.core.osm;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.google.protobuf.ByteString;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.util.List;
import java.util.zip.Deflater;
import no.nvdbincline.core.ProgressCallback;
import no.nvdbincline.core.geo.LonLatMultiPolygon;
import org.junit.jupiter.api.Test;
import org.openstreetmap.osmosis.osmbinary.Fileformat;
import org.openstreetmap.osmosis.osmbinary.Osmformat;

class PbfHighwayExtractorTest {

    @Test
    void extractsInsideAndStraddlingExcludesOutside() throws Exception {
        LonLatMultiPolygon poly =
                new LonLatMultiPolygon(
                        List.of(
                                new LonLatMultiPolygon.Polygon(
                                        List.of(
                                                new LonLatMultiPolygon.Ring(
                                                        List.of(
                                                                new double[] {10.0, 60.0},
                                                                new double[] {11.0, 60.0},
                                                                new double[] {11.0, 61.0},
                                                                new double[] {10.0, 61.0},
                                                                new double[] {10.0, 60.0}))))));

        // nodes: 1 inside, 2 inside, 3 outside, 4 outside far
        byte[] pbf =
                writeTinyPbf(
                        new NodeSpec(1, 10.2, 60.2),
                        new NodeSpec(2, 10.3, 60.3),
                        new NodeSpec(3, 11.02, 60.5), // outside polygon, inside pad
                        new NodeSpec(4, 12.5, 62.5), // far outside
                        new WaySpec(100, "residential", 1, 2), // inside
                        new WaySpec(101, "residential", 1, 3), // straddling -> include
                        new WaySpec(102, "residential", 3, 4), // no node inside polygon
                        new WaySpec(103, "footway", 1, 2)); // excluded type

        PbfHighwayExtractor.Result result =
                PbfHighwayExtractor.extract(
                        new DataInputStream(new ByteArrayInputStream(pbf)),
                        pbf.length,
                        poly,
                        ProgressCallback.NONE);
        assertEquals(2, result.highways.size());
        assertTrue(result.highways.stream().anyMatch(h -> h.id == 100));
        assertTrue(result.highways.stream().anyMatch(h -> h.id == 101));
    }

    private record NodeSpec(long id, double lon, double lat) {}

    private record WaySpec(long id, String highway, long... refs) {}

    private static byte[] writeTinyPbf(Object... items) throws Exception {
        Osmformat.StringTable.Builder st =
                Osmformat.StringTable.newBuilder().addS(ByteString.copyFromUtf8(""));
        int highwayKey = addString(st, "highway");

        Osmformat.PrimitiveGroup.Builder nodes = Osmformat.PrimitiveGroup.newBuilder();
        Osmformat.PrimitiveGroup.Builder ways = Osmformat.PrimitiveGroup.newBuilder();
        for (Object o : items) {
            if (o instanceof NodeSpec n) {
                // Decode: lat = 1e-9 * (offset + granularity * stored); granularity=100
                long lat = Math.round(n.lat * 1e7);
                long lon = Math.round(n.lon * 1e7);
                nodes.addNodes(
                        Osmformat.Node.newBuilder().setId(n.id).setLat(lat).setLon(lon).build());
            } else if (o instanceof WaySpec w) {
                int highwayVal = addString(st, w.highway);
                Osmformat.Way.Builder wb =
                        Osmformat.Way.newBuilder()
                                .setId(w.id)
                                .addKeys(highwayKey)
                                .addVals(highwayVal);
                long prev = 0;
                for (long ref : w.refs) {
                    wb.addRefs(ref - prev);
                    prev = ref;
                }
                ways.addWays(wb.build());
            }
        }
        Osmformat.PrimitiveBlock block =
                Osmformat.PrimitiveBlock.newBuilder()
                        .setStringtable(st)
                        .setGranularity(100)
                        .setLatOffset(0)
                        .setLonOffset(0)
                        .addPrimitivegroup(nodes)
                        .addPrimitivegroup(ways)
                        .build();
        byte[] raw = block.toByteArray();
        byte[] zlib = zlib(raw);
        Fileformat.Blob blob =
                Fileformat.Blob.newBuilder()
                        .setZlibData(ByteString.copyFrom(zlib))
                        .setRawSize(raw.length)
                        .build();
        Fileformat.BlobHeader header =
                Fileformat.BlobHeader.newBuilder()
                        .setType("OSMData")
                        .setDatasize(blob.getSerializedSize())
                        .build();
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        DataOutputStream out = new DataOutputStream(bos);
        byte[] hb = header.toByteArray();
        out.writeInt(hb.length);
        out.write(hb);
        out.write(blob.toByteArray());
        out.flush();
        return bos.toByteArray();
    }

    private static int addString(Osmformat.StringTable.Builder st, String s) {
        st.addS(ByteString.copyFromUtf8(s));
        return st.getSCount() - 1;
    }

    private static byte[] zlib(byte[] raw) {
        Deflater def = new Deflater();
        def.setInput(raw);
        def.finish();
        byte[] buf = new byte[raw.length + 64];
        int n = def.deflate(buf);
        byte[] out = new byte[n];
        System.arraycopy(buf, 0, out, 0, n);
        return out;
    }
}
