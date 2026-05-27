package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.FollowMapper;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class FollowServiceImpl extends ServiceImpl<FollowMapper, Follow> implements IFollowService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private IUserService userService;

    @Override
    @Transactional
    public Result follow(Long followUserId, Boolean isFollow) {
        // 关注关系以 MySQL 为准，同时维护 Redis Set，方便快速判断关注和求共同关注。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Long userId = user.getId();
        String key = RedisConstants.FOLLOWS_KEY + userId;

        if (Boolean.TRUE.equals(isFollow)) {
            int count = query().eq("user_id", userId).eq("follow_user_id", followUserId).count();
            if (count > 0) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
                return Result.ok();
            }
            Follow follow = new Follow();
            follow.setUserId(userId);
            follow.setFollowUserId(followUserId);
            boolean success = save(follow);
            if (success) {
                stringRedisTemplate.opsForSet().add(key, followUserId.toString());
            }
        } else {
            boolean success = remove(query()
                    .eq("user_id", userId)
                    .eq("follow_user_id", followUserId)
                    .getWrapper());
            if (success) {
                stringRedisTemplate.opsForSet().remove(key, followUserId.toString());
            }
        }
        return Result.ok();
    }

    @Override
    public Result isFollow(Long followUserId) {
        // 先查 Redis Set，未命中再查数据库，兼容缓存缺失或历史数据未预热的情况。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        String key = RedisConstants.FOLLOWS_KEY + user.getId();
        Boolean member = stringRedisTemplate.opsForSet().isMember(key, followUserId.toString());
        if (Boolean.TRUE.equals(member)) {
            return Result.ok(true);
        }
        int count = query()
                .eq("user_id", user.getId())
                .eq("follow_user_id", followUserId)
                .count();
        return Result.ok(count > 0);
    }

    @Override
    public Result followCommons(Long otherUserId) {
        // Redis SINTER 直接求两个关注集合的交集，得到共同关注用户 ID。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        String key1 = RedisConstants.FOLLOWS_KEY + user.getId();
        String key2 = RedisConstants.FOLLOWS_KEY + otherUserId;
        Set<String> intersect = stringRedisTemplate.opsForSet().intersect(key1, key2);
        if (CollUtil.isEmpty(intersect)) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = intersect.stream().map(Long::valueOf).collect(Collectors.toList());
        List<UserDTO> users = userService.listByIds(ids).stream()
                .map(userInfo -> BeanUtil.copyProperties(userInfo, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(users);
    }
}
