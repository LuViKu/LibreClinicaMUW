/*
 * LibreClinica is distributed under the
 * GNU Lesser General Public License (GNU LGPL).
 *
 * For details see: https://libreclinica.org/license
 * copyright (C) 2026 Department of Ophthalmology and Optometry,
 *                     Medical University of Vienna
 */
package at.ac.meduniwien.ophthalmology.libreclinica.service.retinal.io;

import java.awt.image.BufferedImage;
import java.awt.image.Raster;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.TreeMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.metadata.IIOMetadata;
import javax.imageio.stream.ImageInputStream;

import org.w3c.dom.Node;

import at.ac.meduniwien.ophthalmology.libreclinica.core.util.Json;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;


/**
 * 2026-10-07 — reads SD-RetinaNet output in the OPTIMA group's native
 * formats, the same files the SWITCHER study and iamd-ws consume:
 *
 * <pre>
 *   layers/NNN.yml    one per B-scan (layerlib)
 *   lesions/NNN.png   one per B-scan (lesionlib)
 * </pre>
 *
 * either as the {@code sdretinanet.zip} artifact the inference sidecar ships
 * or as an extracted folder.
 *
 * <p><b>layerlib .yml</b> is a line format, not general YAML:
 * <pre>
 *   info:
 *     width: 512
 *     height: 496
 *   layers:
 *     - ILM:
 *       - values: 0,0,126,...      depth row per A-scan
 *       - confid: 0,0,1,...        0 = invalid
 *       - uncrtn: ...              optional, ignored here
 * </pre>
 * Layer names and their order come from the file.
 *
 * <p><b>lesionlib .png</b> is an 8-bit indexed PNG whose pixel <i>index</i>
 * packs the masks (see {@link SdRetinaNetVolume}); the palette is cosmetic.
 * A compressed text chunk {@code Lesions} holds
 * {@code {"v":"1","bc":6,"mc":6,"oc":1,"mn":[...],"on":["HRF"]}}: the bit
 * count, the main and overlay counts and their names. The PNG writer may store
 * fewer than 8 bits per pixel; {@link Raster#getSample} returns the index
 * either way.
 */
public final class SdRetinaNetReader {

    /** Artifact name the sidecar ships. */
    public static final String ARCHIVE = "sdretinanet.zip";

    private static final Pattern LAYER_FILE = Pattern.compile("(?:^|/)layers/(\\d+)\\.yml$");
    private static final Pattern LESION_FILE = Pattern.compile("(?:^|/)lesions/(\\d+)\\.png$");
    private static final Pattern INFO_KV = Pattern.compile("^([A-Za-z0-9_]+)\\s*:\\s*(.+)$");
    private static final Pattern LAYER_DEF = Pattern.compile("^-\\s*([A-Za-z0-9_\\-]+):$");
    private static final Pattern LAYER_ROW = Pattern.compile("^-\\s*(values|confid|uncrtn):\\s*(.*)$");
    private static final String LESION_META_KEY = "Lesions";
    private static final JsonMapper JSON = Json.mapper();

    private SdRetinaNetReader() { }

    /** True when {@code dir} holds SD-RetinaNet output in either form. */
    public static boolean present(Path dir) {
        return Files.isRegularFile(dir.resolve(ARCHIVE)) || Files.isDirectory(dir.resolve("layers"));
    }

    /**
     * Read the segmentation under {@code dir}: {@value #ARCHIVE} if present,
     * else {@code layers/} + {@code lesions/} subfolders.
     */
    public static SdRetinaNetVolume read(Path dir) throws IOException {
        TreeMap<Integer, byte[]> layers = new TreeMap<>();
        TreeMap<Integer, byte[]> lesions = new TreeMap<>();
        Path zip = dir.resolve(ARCHIVE);
        if (Files.isRegularFile(zip)) {
            try (InputStream raw = Files.newInputStream(zip); ZipInputStream zin = new ZipInputStream(raw)) {
                ZipEntry e;
                while ((e = zin.getNextEntry()) != null) {
                    if (!e.isDirectory()) {
                        collect(e.getName().replace('\\', '/'), zin.readAllBytes(), layers, lesions);
                    }
                }
            }
        } else {
            for (String sub : new String[]{"layers", "lesions"}) {
                Path d = dir.resolve(sub);
                if (!Files.isDirectory(d)) continue;
                List<Path> files;
                try (Stream<Path> s = Files.list(d)) {
                    files = s.filter(Files::isRegularFile).toList();
                }
                for (Path f : files) {
                    String name = sub + "/" + f.getFileName();
                    if (LAYER_FILE.matcher(name).find() || LESION_FILE.matcher(name).find()) {
                        collect(name, Files.readAllBytes(f), layers, lesions);
                    }
                }
            }
        }
        return assemble(layers, lesions);
    }

    private static void collect(String name, byte[] bytes,
                                TreeMap<Integer, byte[]> layers, TreeMap<Integer, byte[]> lesions) {
        Matcher m = LAYER_FILE.matcher(name);
        if (m.find()) {
            layers.put(Integer.parseInt(m.group(1)), bytes);
            return;
        }
        m = LESION_FILE.matcher(name);
        if (m.find()) {
            lesions.put(Integer.parseInt(m.group(1)), bytes);
        }
    }

    private static SdRetinaNetVolume assemble(TreeMap<Integer, byte[]> layerFiles,
                                              TreeMap<Integer, byte[]> lesionFiles) throws IOException {
        int n = layerFiles.size();
        if (n == 0) {
            throw new IOException("no layers/NNN.yml files in SD-RetinaNet output");
        }
        if (layerFiles.firstKey() != 0 || layerFiles.lastKey() != n - 1) {
            throw new IOException("layer files are not numbered 0.." + (n - 1));
        }
        if (lesionFiles.size() != n || !lesionFiles.keySet().equals(layerFiles.keySet())) {
            throw new IOException("incomplete SD-RetinaNet output: " + n + " layer / "
                    + lesionFiles.size() + " lesion files");
        }

        List<String> layerNames = null;
        int width = -1;
        int height = -1;
        float[] boundaries = null;
        for (int b = 0; b < n; b++) {
            LayerFile lf = parseLayers(new String(layerFiles.get(b), StandardCharsets.UTF_8), b);
            if (layerNames == null) {
                layerNames = lf.names;
                width = lf.width;
                height = lf.height;
                boundaries = new float[n * layerNames.size() * width];
            } else if (!lf.names.equals(layerNames) || lf.width != width || lf.height != height) {
                throw new IOException("layers/" + b + ".yml disagrees with B-scan 0 on layer names or size");
            }
            System.arraycopy(lf.values, 0, boundaries, b * layerNames.size() * width, lf.values.length);
        }

        byte[] packed = new byte[n * height * width];
        List<String> main = null;
        List<String> overlay = null;
        for (int b = 0; b < n; b++) {
            LesionFile lf = parseLesions(lesionFiles.get(b), b);
            if (lf.width != width || lf.height != height) {
                throw new IOException("lesions/" + b + ".png is " + lf.width + "x" + lf.height
                        + ", layers are " + width + "x" + height);
            }
            if (main == null) {
                main = lf.main;
                overlay = lf.overlay;
            } else if (!lf.main.equals(main) || !lf.overlay.equals(overlay)) {
                throw new IOException("lesions/" + b + ".png disagrees with B-scan 0 on lesion names");
            }
            System.arraycopy(lf.packed, 0, packed, b * height * width, lf.packed.length);
        }
        return new SdRetinaNetVolume(n, width, height, List.copyOf(layerNames), boundaries,
                List.copyOf(main), List.copyOf(overlay), packed);
    }

    private record LayerFile(int width, int height, List<String> names, float[] values) { }

    private static LayerFile parseLayers(String text, int bscan) throws IOException {
        String section = null;
        Integer width = null;
        Integer height = null;
        List<String> names = new ArrayList<>();
        List<float[]> values = new ArrayList<>();
        List<int[]> confid = new ArrayList<>();
        for (String raw : text.split("\\R")) {
            int hash = raw.indexOf('#');
            String line = (hash >= 0 ? raw.substring(0, hash) : raw).strip();
            if (line.isEmpty()) continue;
            if (line.equals("info:") || line.equals("layers:")) {
                section = line.substring(0, line.length() - 1);
                continue;
            }
            if ("info".equals(section)) {
                Matcher kv = INFO_KV.matcher(line);
                if (kv.matches()) {
                    if (kv.group(1).equals("width")) width = parseInt(kv.group(2), bscan);
                    if (kv.group(1).equals("height")) height = parseInt(kv.group(2), bscan);
                }
            } else if ("layers".equals(section)) {
                Matcher row = LAYER_ROW.matcher(line);
                if (row.matches()) {
                    if (names.isEmpty()) {
                        throw new IOException("layers/" + bscan + ".yml: '" + row.group(1) + "' before any layer");
                    }
                    int i = names.size() - 1;
                    switch (row.group(1)) {
                        case "values" -> values.set(i, parseFloats(row.group(2), bscan));
                        case "confid" -> confid.set(i, parseInts(row.group(2), bscan));
                        default -> { /* uncertainty is not used */ }
                    }
                    continue;
                }
                Matcher def = LAYER_DEF.matcher(line);
                if (def.matches()) {
                    names.add(def.group(1));
                    values.add(null);
                    confid.add(null);
                }
            }
        }
        if (width == null || height == null) {
            throw new IOException("layers/" + bscan + ".yml: info.width/height missing");
        }
        if (names.isEmpty()) {
            throw new IOException("layers/" + bscan + ".yml: no layers");
        }
        float[] out = new float[names.size() * width];
        for (int l = 0; l < names.size(); l++) {
            float[] v = values.get(l);
            int[] c = confid.get(l);
            if (v == null || v.length != width) {
                throw new IOException("layers/" + bscan + ".yml: layer " + names.get(l) + " has "
                        + (v == null ? 0 : v.length) + " values, expected " + width);
            }
            if (c != null && c.length != width) {
                throw new IOException("layers/" + bscan + ".yml: layer " + names.get(l)
                        + " has " + c.length + " confidence values, expected " + width);
            }
            for (int a = 0; a < width; a++) {
                out[l * width + a] = (c != null && c[a] == 0) ? Float.NaN : v[a];
            }
        }
        return new LayerFile(width, height, names, out);
    }

    private record LesionFile(int width, int height, List<String> main, List<String> overlay, byte[] packed) { }

    private static LesionFile parseLesions(byte[] png, int bscan) throws IOException {
        try (ImageInputStream in = ImageIO.createImageInputStream(new ByteArrayInputStream(png))) {
            Iterator<ImageReader> readers = ImageIO.getImageReadersByFormatName("png");
            if (!readers.hasNext()) throw new IOException("no PNG reader available");
            ImageReader reader = readers.next();
            try {
                reader.setInput(in, true, false);
                String metaJson = textChunk(reader.getImageMetadata(0), LESION_META_KEY);
                if (metaJson == null) {
                    throw new IOException("lesions/" + bscan + ".png has no '" + LESION_META_KEY + "' metadata");
                }
                JsonNode meta;
                try {
                    meta = JSON.readTree(metaJson);
                } catch (JacksonException e) {
                    throw new IOException("lesions/" + bscan + ".png: unreadable '" + LESION_META_KEY + "' metadata", e);
                }
                int mc = meta.path("mc").asInt(-1);
                int oc = meta.path("oc").asInt(-1);
                int bc = meta.path("bc").asInt(6);
                List<String> main = names(meta.path("mn"), mc, "L");
                List<String> overlay = names(meta.path("on"), oc, "Ov");
                if (mc < 0 || oc < 0 || mc >= (1 << bc) || oc > 8 - bc) {
                    throw new IOException("lesions/" + bscan + ".png: unsupported packing " + metaJson);
                }
                BufferedImage img = reader.read(0);
                Raster r = img.getRaster();
                if (r.getNumBands() != 1) {
                    throw new IOException("lesions/" + bscan + ".png is not an indexed/grey image");
                }
                int w = r.getWidth();
                int h = r.getHeight();
                byte[] packed = new byte[w * h];
                int[] row = new int[w];
                for (int y = 0; y < h; y++) {
                    r.getSamples(0, y, w, 1, 0, row);
                    for (int x = 0; x < w; x++) packed[y * w + x] = (byte) row[x];
                }
                return new LesionFile(w, h, main, overlay, packed);
            } finally {
                reader.dispose();
            }
        }
    }

    /** Names from the metadata, or {@code L1..}/{@code Ov1..} as lesionlib does when they are missing. */
    private static List<String> names(JsonNode arr, int count, String prefix) {
        List<String> out = new ArrayList<>();
        if (arr.isArray() && arr.size() == count) {
            arr.forEach(n -> out.add(Json.text(n)));
        } else {
            for (int i = 1; i <= count; i++) out.add(prefix + i);
        }
        return out;
    }

    /** A tEXt/zTXt/iTXt value from the native PNG metadata tree; ImageIO inflates zTXt itself. */
    private static String textChunk(IIOMetadata md, String keyword) {
        if (md == null) return null;
        Node root = md.getAsTree("javax_imageio_png_1.0");
        for (Node chunk = root.getFirstChild(); chunk != null; chunk = chunk.getNextSibling()) {
            String chunkName = chunk.getNodeName();
            if (!chunkName.equals("tEXt") && !chunkName.equals("zTXt") && !chunkName.equals("iTXt")) continue;
            for (Node e = chunk.getFirstChild(); e != null; e = e.getNextSibling()) {
                Node kw = e.getAttributes().getNamedItem("keyword");
                if (kw == null || !keyword.equals(kw.getNodeValue())) continue;
                // tEXtEntry carries "value", zTXtEntry and iTXtEntry carry "text"
                Node text = e.getAttributes().getNamedItem(chunkName.equals("tEXt") ? "value" : "text");
                if (text != null) return text.getNodeValue();
            }
        }
        return null;
    }

    private static int parseInt(String s, int bscan) throws IOException {
        try {
            return Integer.parseInt(s.strip());
        } catch (NumberFormatException e) {
            throw new IOException("layers/" + bscan + ".yml: bad integer '" + s + "'", e);
        }
    }

    private static float[] parseFloats(String csv, int bscan) throws IOException {
        if (csv.isBlank()) return new float[0];
        String[] parts = csv.split(",");
        float[] out = new float[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Float.parseFloat(parts[i].strip());
        } catch (NumberFormatException e) {
            throw new IOException("layers/" + bscan + ".yml: bad value list", e);
        }
        return out;
    }

    private static int[] parseInts(String csv, int bscan) throws IOException {
        if (csv.isBlank()) return new int[0];
        String[] parts = csv.split(",");
        int[] out = new int[parts.length];
        try {
            for (int i = 0; i < parts.length; i++) out[i] = Integer.parseInt(parts[i].strip());
        } catch (NumberFormatException e) {
            throw new IOException("layers/" + bscan + ".yml: bad confidence list", e);
        }
        return out;
    }
}
