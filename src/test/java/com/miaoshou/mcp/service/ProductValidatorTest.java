package com.miaoshou.mcp.service;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.miaoshou.mcp.Fixtures;
import com.miaoshou.mcp.common.BusinessException;
import com.miaoshou.mcp.domain.ProductRef;
import org.junit.jupiter.api.Test;

class ProductValidatorTest {
    private final ProductValidator validator = new ProductValidator();

    @Test
    void acceptsSortedKeysWhoseIdOrderDiffersFromDimensionOrder() throws Exception {
        var envelope = Fixtures.envelope();
        assertThatCode(() -> validator.validate(envelope, new ProductRef(17, 23))).doesNotThrowAnyException();
    }

    @Test
    void rejectsWrongShopBeforeCreatingSnapshot() throws Exception {
        var envelope = Fixtures.envelope();
        assertThatThrownBy(() -> validator.validate(envelope, new ProductRef(17, 24)))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsUnknownValueIdInKey() throws Exception {
        ObjectNode product = Fixtures.product();
        ObjectNode map = (ObjectNode) product.get("skuMap");
        map.set(";a;unknown;", map.remove(";a;b;"));
        assertThatThrownBy(() -> validator.validateSku(product)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsRepeatedDimensionAndDuplicateCombination() throws Exception {
        ObjectNode product = Fixtures.product();
        ObjectNode map = (ObjectNode) product.get("skuMap");
        map.set(";b;a;", map.get(";a;b;").deepCopy());
        assertThatThrownBy(() -> validator.validateSku(product)).isInstanceOf(BusinessException.class);
        map.remove(";b;a;");
        map.set(";a;c;", map.remove(";a;b;"));
        assertThatThrownBy(() -> validator.validateSku(product)).isInstanceOf(BusinessException.class);
    }

    @Test
    void rejectsPropertyIdMutationEvenIfTheMapIsUnchanged() throws Exception {
        ObjectNode original = Fixtures.product();
        ObjectNode draft = original.deepCopy();
        ((ObjectNode) draft.at("/skuPropertyList/0")).put("attrId", "changed");
        assertThatThrownBy(() -> validator.assertIdentityUnchanged(original, draft))
                .isInstanceOf(BusinessException.class);
    }

    @Test
    void allowsSparseCombinationSets() throws Exception {
        ObjectNode product = Fixtures.product();
        ((ObjectNode) product.get("skuMap")).remove(";a;b;");
        assertThatCode(() -> validator.validateSku(product)).doesNotThrowAnyException();
    }

    @Test
    void rejectsEmptyCombinationKeyWithBusinessError() throws Exception {
        ObjectNode product = Fixtures.product();
        ObjectNode map = (ObjectNode) product.get("skuMap");
        map.set(";", map.remove(";a;b;"));
        assertThatThrownBy(() -> validator.validateSku(product))
                .isInstanceOf(BusinessException.class).hasMessage("Invalid SKU map key or payload");
    }
}
