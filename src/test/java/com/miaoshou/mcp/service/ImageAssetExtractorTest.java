package com.miaoshou.mcp.service;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.domain.SnapshotRow;
import com.miaoshou.mcp.domain.image.ImageAsset;
import com.miaoshou.mcp.domain.image.ImageReference;
import java.util.Comparator;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ImageAssetExtractorTest {
    private final ImageAssetExtractor extractor = new ImageAssetExtractor();

    private SnapshotRow row(String id) {
        return new SnapshotRow(id, 1, 2, "SG", "baseline", "{}", "2026-11-08T00:00:00Z");
    }

    private ObjectNode empty() {
        return Fixtures.JSON.createObjectNode().put("notes", "<p>text</p>");
    }

    @Test
    void emptyCollectionsAndAbsentImagesAreValid() {
        var product = empty();
        product.putArray("imgUrls");
        var result = extractor.extract(row("one"), product);
        assertThat(result.totalAssets()).isZero();
        assertThat(result.totalReferences()).isZero();
        assertThat(result.validation().valid()).isTrue();
        assertThat(extractor.extract(row("two"), empty()).assets()).isEmpty();
    }

    @Test
    void deduplicatesAcrossRolesButKeepsEveryOccurrenceAndOriginalOrder() {
        var product = empty().put("notes", "<img src='https://a.test/b'><img src='https://a.test/a'><img src='https://a.test/a'>");
        product.putArray("imgUrls").add("https://a.test/a").add("https://a.test/b").add("https://a.test/a");
        var result = extractor.extract(row("one"), product);
        assertThat(result.totalAssets()).isEqualTo(2);
        assertThat(result.totalReferences()).isEqualTo(6);
        assertThat(result.assets().getFirst().references()).hasSize(4);
        assertThat(result.assets().getFirst().assetTypes()).hasSize(2);
        assertThat(result.summary().unclassified()).isEqualTo(3);
        assertThat(result.summary().detailImages()).isEqualTo(3);
        assertThat(result.summary().mainImages()).isZero();
        assertThat(result.summary().candidateMainImages()).isEqualTo(1);
        var refs = result.assets().stream().flatMap(a -> a.references().stream())
                .sorted(Comparator.comparingInt(ImageReference::sequence)).toList();
        assertThat(refs.stream().map(ImageReference::sourcePath)).containsExactly(
                "/imgUrls/0", "/imgUrls/1", "/imgUrls/2", "/notes", "/notes", "/notes");
        assertThat(refs.stream().skip(3).map(ImageReference::htmlImageIndex)).containsExactly(0, 1, 2);
    }

    @Test
    void skipsEmptyButKeepsRawHtmlIndices() {
        var product = empty().put("notes", "<img><img src=''><img src='https://a.test/a'>");
        product.putArray("imgUrls").add("").addNull().add("  ");
        var result = extractor.extract(row("one"), product);
        assertThat(result.skippedEmptyReferences()).isEqualTo(5);
        assertThat(result.totalReferences()).isEqualTo(1);
        assertThat(result.assets().getFirst().references().getFirst().htmlImageIndex()).isEqualTo(2);
        assertThat(result.summary().candidateMainImages()).isZero();
    }

    @Test
    void invalidUrlsAreRetainedWithSafeErrorsAndDoNotContactAnyHost() {
        var product = empty();
        product.putArray("imgUrls").add("javascript:alert(1)").add("/relative.png")
                .add("https://a.test/bad space").add("https://user:secret@a.test/a").add("file:///tmp/a")
                .add("https://127.0.0.1:1/image");
        var result = extractor.extract(row("one"), product);
        assertThat(result.totalAssets()).isEqualTo(6);
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().errors()).hasSize(5);
        assertThat(result.assets().stream().filter(a -> a.status() == ImageAsset.Status.INVALID_URL)).hasSize(5);
        assertThat(result.validation().toString()).doesNotContain("secret", "alert");
        // A syntactically valid URL is accepted locally even when unreachable. No connectivity assertion is made.
        assertThat(result.assets().getLast().status()).isEqualTo(ImageAsset.Status.PENDING);
    }

    @Test
    void wrongJsonTypesAreReportedWithoutCoercion() {
        var product = empty().put("notes", 42);
        product.putArray("imgUrls").add(12).addObject().put("url", "https://a.test/a");
        var result = extractor.extract(row("one"), product);
        assertThat(result.validation().errors()).hasSize(3);
        assertThat(result.assets()).isEmpty();
        product.put("imgUrls", "https://a.test/a");
        assertThat(extractor.extract(row("one"), product).validation().errors())
                .anyMatch(issue -> issue.code().equals("EXPECTED_ARRAY"));
    }

    @Test
    void malformedHtmlIsParsedWithoutRewritingSource() {
        var product = empty().put("notes", "<div><p><img src=https://a.test/a><broken><img src='https://a.test/b'>");
        var before = product.toString();
        assertThat(extractor.extract(row("one"), product).summary().detailImages()).isEqualTo(2);
        assertThat(product.toString()).isEqualTo(before);
    }

    @Test
    void preservesUrlQueriesEscapesCaseAndWhitespaceSemantics() {
        var product = empty().put("notes", "<img src='https://a.test/a?x=1&amp;y=%2F#frag'>");
        product.putArray("imgUrls").add("https://a.test/a?x=1&y=%2F#frag")
                .add("https://a.test/a?y=%2F&x=1#frag").add("HTTPS://a.test/A");
        var result = extractor.extract(row("one"), product);
        assertThat(result.totalAssets()).isEqualTo(3);
        assertThat(result.assets().getFirst().originalUrl()).isEqualTo("https://a.test/a?x=1&y=%2F#frag");
        assertThat(result.assets().getFirst().references()).hasSize(2);
        assertThat(result.validation().valid()).isTrue();
    }

    @Test
    void stableIdsAreScopedToSnapshotAndContentHashRemainsUnknown() {
        var product = empty();
        product.putArray("imgUrls").add("https://a.test/a");
        var first = extractor.extract(row("one"), product);
        assertThat(extractor.extract(row("one"), product)).isEqualTo(first);
        assertThat(first.assets().getFirst().assetId()).matches("IMG-[a-f0-9]{64}");
        assertThat(first.assets().getFirst().sha256()).isNull();
        assertThat(extractor.extract(row("two"), product).assets().getFirst().assetId())
                .isNotEqualTo(first.assets().getFirst().assetId());
        assertThatThrownBy(() -> first.assets().clear()).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(() -> first.assets().getFirst().references().clear()).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void rfc6901EscapesOnlyTildeAndSlashNotSemicolons() {
        assertThat(ImageAssetExtractor.pointerToken(";a/b~c;" )).isEqualTo(";a~1b~0c;");
        assertThat(ImageAssetExtractor.pointerToken(";123;" )).isEqualTo(";123;");
    }

    @Test
    void multiDimensionImagesKeepAllOriginalKeysWithoutDuplicatingPhysicalReferences() throws Exception {
        var product = Fixtures.product();
        var before = product.toString();
        new ProductValidator().validateSku(product);
        var result = extractor.extract(row("multi"), product);
        var skuRefs = result.assets().stream().flatMap(a -> a.references().stream())
                .filter(r -> r.referenceType() == ImageReference.ReferenceType.SKU_IMAGE).toList();
        assertThat(skuRefs).hasSize(2);
        assertThat(skuRefs.get(0).skuMapKeys()).containsExactly(";a;b;", ";a;d;");
        assertThat(skuRefs.get(1).skuMapKeys()).containsExactly(";b;c;", ";c;d;");
        for (var asset : result.assets()) {
            for (var ref : asset.references()) {
                assertThat(product.at(ref.sourcePath()).asText()).isEqualTo(asset.originalUrl());
            }
        }
        assertThat(result.totalAssets()).isEqualTo(2);
        assertThat(result.totalReferences()).isEqualTo(4);
        assertThat(result.skippedEmptyReferences()).isEqualTo(2);
        assertThat(product.toString()).isEqualTo(before);
    }

    @ParameterizedTest
    @ValueSource(ints = {1, 4, 26})
    void singleAndManySkuImagesRetainEachPhysicalPositionWhenUrlIsShared(int count) {
        var product = empty();
        var values = product.putArray("skuPropertyList").addObject().putArray("attrValueList");
        var skuMap = product.putObject("skuMap");
        for (int i = 0; i < count; i++) {
            values.addObject().put("attrValueId", "id" + i).put("imgUrl", "https://a.test/shared");
            skuMap.putObject(";id" + i + ";").put("stock", i);
        }
        new ProductValidator().validateSku(product);
        var before = product.toString();
        var result = extractor.extract(row("many"), product);
        assertThat(result.totalAssets()).isEqualTo(1);
        assertThat(result.totalReferences()).isEqualTo(count);
        assertThat(result.summary().skuImages()).isEqualTo(count);
        assertThat(result.assets().getFirst().references().getLast().skuMapKeys())
                .containsExactly(";id" + (count - 1) + ";");
        assertThat(product.toString()).isEqualTo(before);
    }

    @Test
    void specialSkuIdsMatchExactTokensAndKeepRawKeys() {
        var product = empty();
        var values = product.putArray("skuPropertyList").addObject().putArray("attrValueList");
        values.addObject().put("attrValueId", "a/b~c").put("imgUrl", "https://a.test/a");
        values.addObject().put("attrValueId", "a/b~cx").putNull("imgUrl");
        product.putObject("skuMap").putObject(";a/b~c;");
        ((ObjectNode) product.path("skuMap")).putObject(";a/b~cx;");
        new ProductValidator().validateSku(product);
        var result = extractor.extract(row("special"), product);
        assertThat(result.assets().getFirst().references().getFirst().skuMapKeys()).containsExactly(";a/b~c;");
    }

    @Test
    void unsupportedSkuImageFieldsAreExplicitErrorsWithEscapedPaths() throws Exception {
        var product = Fixtures.product();
        ((ObjectNode) product.at("/skuPropertyList/0/attrValueList/0"))
                .putArray("supplementarySkuImageUrls").add("https://a.test/extra");
        ((ObjectNode) product.path("skuMap")).putObject(";a/b~c;").put("imgUrl", "https://a.test/direct");
        var result = extractor.extract(row("unknown"), product);
        assertThat(result.validation().valid()).isFalse();
        assertThat(result.validation().errors()).extracting(e -> e.sourcePath()).containsExactly(
                "/skuPropertyList/0/attrValueList/0/supplementarySkuImageUrls", "/skuMap/;a~1b~0c;/imgUrl");
    }

    @Test
    void emptySkuImagesDoNotCreateAssets() throws Exception {
        var product = Fixtures.product();
        product.putArray("imgUrls");
        for (var property : product.path("skuPropertyList")) {
            for (var value : property.path("attrValueList")) { ((ObjectNode) value).put("imgUrl", ""); }
        }
        var result = extractor.extract(row("empty-sku"), product);
        assertThat(result.assets()).isEmpty();
        assertThat(result.skippedEmptyReferences()).isEqualTo(4);
        assertThat(result.validation().valid()).isTrue();
    }
}
