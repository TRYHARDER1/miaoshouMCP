# 开放接口与业务映射

正式域名为 `https://openapi-erp.91miaoshou.com`。所有已验证接口采用 POST，认证为 x-app-key、x-timestamp 与 x-sign。

签名为 `HmacSHA256(secret, secret + path + timestamp + appKey + bodyJson + secret)`，签名参与的 UTF-8 字节与发送请求一致，输出小写十六进制。

| 业务 | 路径 | 初版 |
| --- | --- | --- |
| 店铺列表 | `/open/v1/product/shop/shop/get_shop_list` | 已封装 |
| TK 采集箱 | `/open/v1/product/collect_box/tiktok/collect_box/search_collect_box_detail_list` | 已封装 |
| 店铺模式详情 | `/open/v1/product/collect_box/tiktok/collect_box/get_shop_collect_item_info` | 已封装 |
| 定价模板列表 | `/open/v1/product/collect_box/tiktok/price_template/get_price_template_list` | 已封装 |
| 站点默认配置 | `/open/v1/product/collect_box/tiktok/price_template/get_site_default_setting` | M6 |
| 完整对象保存 | `/open/v1/product/collect_box/tiktok/collect_box/save_shop_collect_item_info` | M7 |
| URL 上传图片空间 | `/open/v1/product/picture/picture_space/upload_picture_link` | M5 |

## 数据边界

- 列表使用账号级分页，在每个源页内根据 collectBoxDetailShopList 中的 shopId 过滤。
- 详情核对 detailId、shopId、editModel、ossMd5 和已认领店铺。
- 原始 data 完整保存。shopCollectItemInfo 供规则读取，未知字段保留。
- 重量单位为 kg，尺寸单位为 cm。price、priceIncludeVat、originPrice 独立保存，默认无自动联动。
- 已验证的保存适配为补充 deliveryOptionSetType=default，并省略空尺码表相关字段。该适配将在 M7 统一处理。
