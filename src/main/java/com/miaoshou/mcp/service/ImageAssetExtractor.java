package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.domain.image.ImageAsset;
import com.miaoshou.mcp.domain.image.ImageAssetManifest;
import com.miaoshou.mcp.domain.image.ImageAssetManifest.Issue;
import com.miaoshou.mcp.domain.image.ImageAssetType;
import com.miaoshou.mcp.domain.image.ImageReference;
import com.miaoshou.mcp.domain.image.ImageReference.ReferenceType;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import org.jsoup.Jsoup;
import org.springframework.stereotype.Component;

/** Product-list and description extraction foundation; not yet exposed as an MCP tool. */
@Component
public class ImageAssetExtractor {
    public ImageAssetManifest extract(SnapshotRow row, JsonNode product) {
        State state = new State(row);
        JsonNode images = product.path("imgUrls");
        if (state.arrayOrAbsent(images, "/imgUrls")) {
            for (int i = 0; i < images.size(); i++) {
                state.add(images.get(i), ReferenceType.PRODUCT_IMAGE, ImageAssetType.UNCLASSIFIED,
                        "/imgUrls/" + i, i, List.of(), null, i == 0);
            }
            if (!images.isEmpty()) {
                state.warnings.add(new Issue("PRODUCT_IMAGE_ROLE_UNCONFIRMED", "/imgUrls", null));
            }
        }
        JsonNode notes = product.path("notes");
        if (!notes.isMissingNode() && !notes.isNull() && !notes.isTextual()) {
            state.errors.add(new Issue("EXPECTED_HTML_STRING", "/notes", null));
        } else {
            var elements = Jsoup.parseBodyFragment(notes.asText("")).select("img");
            for (int i = 0; i < elements.size(); i++) {
                state.addText(elements.get(i).attr("src"), ReferenceType.DETAIL_IMAGE,
                        ImageAssetType.DETAIL_IMAGE, "/notes", null, List.of(), i, false);
            }
        }
        return state.manifest();
    }

    public static String pointerToken(String value) {
        return value.replace("~", "~0").replace("/", "~1");
    }

    private static boolean validUrl(String value) {
        try {
            URI uri = URI.create(value);
            return ("http".equalsIgnoreCase(uri.getScheme()) || "https".equalsIgnoreCase(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null;
        } catch (IllegalArgumentException exception) {
            return false;
        }
    }

    private static String assetId(String snapshotId, String url) {
        try {
            var digest = MessageDigest.getInstance("SHA-256");
            digest.update(snapshotId.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            return "IMG-" + HexFormat.of().formatHex(digest.digest(url.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable", exception);
        }
    }

    private static final class State {
        private final SnapshotRow row;
        private final LinkedHashMap<String, List<ImageReference>> urls = new LinkedHashMap<>();
        private final List<Issue> errors = new ArrayList<>();
        private final List<Issue> warnings = new ArrayList<>();
        private int references;
        private int skipped;

        private State(SnapshotRow row) { this.row = row; }

        private boolean arrayOrAbsent(JsonNode node, String path) {
            if (node.isMissingNode() || node.isNull()) {
                return false;
            }
            if (!node.isArray()) {
                errors.add(new Issue("EXPECTED_ARRAY", path, null));
                return false;
            }
            return true;
        }

        private void add(JsonNode node, ReferenceType type, ImageAssetType role, String path,
                         Integer index, List<String> keys, Integer htmlIndex, boolean candidate) {
            if (node.isNull() || node.isMissingNode()) {
                skipped++;
            } else if (!node.isTextual()) {
                errors.add(new Issue("EXPECTED_URL_STRING", path, htmlIndex));
            } else {
                addText(node.textValue(), type, role, path, index, keys, htmlIndex, candidate);
            }
        }

        private void addText(String url, ReferenceType type, ImageAssetType role, String path,
                             Integer index, List<String> keys, Integer htmlIndex, boolean candidate) {
            if (url.isBlank()) {
                skipped++;
                return;
            }
            ImageReference reference = new ImageReference(type, role, path, index, keys,
                    htmlIndex, references++, candidate);
            urls.computeIfAbsent(url, ignored -> new ArrayList<>()).add(reference);
            if (!validUrl(url)) {
                errors.add(new Issue("INVALID_IMAGE_URL", path, htmlIndex));
            }
        }

        private ImageAssetManifest manifest() {
            List<ImageAsset> assets = new ArrayList<>();
            int sku = 0, detail = 0, unclassified = 0, candidates = 0;
            for (var entry : urls.entrySet()) {
                var refs = entry.getValue();
                assets.add(new ImageAsset(assetId(row.id(), entry.getKey()), entry.getKey(),
                        refs.stream().map(ImageReference::assetType).distinct().toList(), assets.size(),
                        refs, null, validUrl(entry.getKey()) ? ImageAsset.Status.PENDING : ImageAsset.Status.INVALID_URL));
                for (var ref : refs) {
                    if (ref.assetType() == ImageAssetType.SKU_IMAGE) { sku++; }
                    if (ref.assetType() == ImageAssetType.DETAIL_IMAGE) { detail++; }
                    if (ref.assetType() == ImageAssetType.UNCLASSIFIED) { unclassified++; }
                    if (ref.candidateMain()) { candidates++; }
                }
            }
            return new ImageAssetManifest(row.id(), row.detailId(), row.shopId(), row.site(),
                    assets.size(), references, skipped, assets,
                    new ImageAssetManifest.Summary(0, 0, sku, detail, unclassified, candidates),
                    new ImageAssetManifest.Validation(errors.isEmpty(), errors, warnings));
        }
    }
}
