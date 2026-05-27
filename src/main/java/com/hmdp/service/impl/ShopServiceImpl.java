package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.entity.Shop;
import com.hmdp.mapper.ShopMapper;
import com.hmdp.service.IShopService;
import com.hmdp.utils.CacheClient;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import org.springframework.data.geo.Circle;
import org.springframework.data.geo.Distance;
import org.springframework.data.geo.GeoResult;
import org.springframework.data.geo.GeoResults;
import org.springframework.data.geo.Metrics;
import org.springframework.data.geo.Point;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.function.Function;
import java.util.stream.Collectors;

import static com.hmdp.dto.Result.ok;
import static com.hmdp.utils.RedisConstants.CACHE_SHOP_KEY;
import static com.hmdp.utils.RedisConstants.LOCK_SHOP_KEY;

@Service
public class ShopServiceImpl extends ServiceImpl<ShopMapper, Shop> implements IShopService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private CacheClient cacheClient;

    @Override
    public Result queryById(Long id) {
        // 商户详情属于高频读取：用逻辑过期缓存抵御热点 key 失效时的大量回源。
        Shop shop = cacheClient.queryWithLogicalExpire(
                CACHE_SHOP_KEY,
                LOCK_SHOP_KEY,
                id,
                Shop.class,
                this::getById,
                20L,
                TimeUnit.SECONDS
        );
        if (shop == null) {
            return Result.fail("店铺不存在");
        }
        return ok(shop);
    }

    @Override
    @Transactional
    public Result update(Shop shop) {
        // 先更新数据库，再删除缓存。后续查询会重新加载，避免缓存长期持有旧数据。
        Long id = shop.getId();
        if (id == null) {
            return Result.fail("店铺id不能为空");
        }
        updateById(shop);
        stringRedisTemplate.delete(CACHE_SHOP_KEY + id);
        return ok();
    }

    @Override
    public Result queryShopByType(Integer typeId, Integer current, Double x, Double y) {
        // 未传坐标时走普通分页；传入坐标时走 Redis GEO，返回附近商户并附带距离。
        if (x == null || y == null) {
            Page<Shop> page = query()
                    .eq("type_id", typeId)
                    .page(new Page<>(current, SystemConstants.DEFAULT_PAGE_SIZE));
            return Result.ok(page.getRecords());
        }

        int from = (current - 1) * SystemConstants.DEFAULT_PAGE_SIZE;
        int end = current * SystemConstants.DEFAULT_PAGE_SIZE;
        String key = RedisConstants.SHOP_GEO_KEY + typeId;
        GeoResults<RedisGeoCommands.GeoLocation<String>> results = stringRedisTemplate.opsForGeo().radius(
                key,
                new Circle(new Point(x, y), new Distance(5, Metrics.KILOMETERS)),
                RedisGeoCommands.GeoRadiusCommandArgs.newGeoRadiusArgs().includeDistance().limit(end)
        );
        if (results == null || results.getContent().size() <= from) {
            return Result.ok(Collections.emptyList());
        }

        List<GeoResult<RedisGeoCommands.GeoLocation<String>>> geoList =
                results.getContent().subList(from, results.getContent().size());
        List<Long> ids = geoList.stream()
                .map(result -> Long.valueOf(result.getContent().getName()))
                .collect(Collectors.toList());
        Map<Long, Double> distanceMap = new HashMap<>();
        geoList.forEach(result -> distanceMap.put(
                Long.valueOf(result.getContent().getName()),
                result.getDistance().getValue()
        ));

        List<Shop> shops = listByIds(ids);
        Map<Long, Shop> shopMap = shops.stream().collect(Collectors.toMap(Shop::getId, Function.identity()));
        // listByIds 不保证返回顺序，这里按 Redis GEO 的距离顺序重新组装结果。
        shops = ids.stream()
                .map(shopMap::get)
                .filter(shop -> shop != null)
                .collect(Collectors.toList());
        shops.forEach(shop -> shop.setDistance(distanceMap.get(shop.getId())));
        return Result.ok(shops);
    }
}
