package com.miaoshou.mcp.client;

public enum ApiEndpoint {
    SHOPS("/open/v1/product/shop/shop/get_shop_list"),
    COLLECT_ITEMS("/open/v1/product/collect_box/tiktok/collect_box/search_collect_box_detail_list"),
    SHOP_DETAIL("/open/v1/product/collect_box/tiktok/collect_box/get_shop_collect_item_info"),
    PRICE_TEMPLATES("/open/v1/product/collect_box/tiktok/price_template/get_price_template_list");

    private final String path;

    ApiEndpoint(String path) {
        this.path = path;
    }

    public String path() {
        return path;
    }
}
