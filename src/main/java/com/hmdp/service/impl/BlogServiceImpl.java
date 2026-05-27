package com.hmdp.service.impl;

import cn.hutool.core.bean.BeanUtil;
import cn.hutool.core.collection.CollUtil;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.hmdp.dto.Result;
import com.hmdp.dto.ScrollResult;
import com.hmdp.dto.UserDTO;
import com.hmdp.entity.Blog;
import com.hmdp.entity.Follow;
import com.hmdp.entity.User;
import com.hmdp.mapper.BlogMapper;
import com.hmdp.service.IBlogService;
import com.hmdp.service.IFollowService;
import com.hmdp.service.IUserService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.SystemConstants;
import com.hmdp.utils.UserHolder;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
public class BlogServiceImpl extends ServiceImpl<BlogMapper, Blog> implements IBlogService {

    @Resource
    private IUserService userService;

    @Resource
    private IFollowService followService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Override
    public Result saveBlog(Blog blog) {
        // 发布笔记后采用推模式：把笔记 ID 写入粉丝的收件箱 ZSet，score 使用发布时间戳。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        blog.setUserId(user.getId());
        boolean success = save(blog);
        if (!success) {
            return Result.fail("发布笔记失败");
        }

        List<Follow> fans = followService.query()
                .eq("follow_user_id", user.getId())
                .list();
        for (Follow fan : fans) {
            if (fan.getUserId().equals(user.getId())) {
                continue;
            }
            String key = RedisConstants.FEED_KEY + fan.getUserId();
            stringRedisTemplate.opsForZSet().add(key, blog.getId().toString(), System.currentTimeMillis());
        }
        return Result.ok(blog.getId());
    }

    @Override
    public Result queryBlogById(Long id) {
        Blog blog = getById(id);
        if (blog == null) {
            return Result.fail("笔记不存在");
        }
        queryBlogUser(blog);
        isBlogLiked(blog);
        return Result.ok(blog);
    }

    @Override
    @Transactional
    public Result likeBlog(Long id) {
        // 点赞状态用 ZSet 记录，score 是点赞时间；数据库 liked 字段只保存计数。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        String userId = user.getId().toString();
        Double score = stringRedisTemplate.opsForZSet().score(key, userId);

        if (score == null) {
            boolean success = update()
                    .setSql("liked = liked + 1")
                    .eq("id", id)
                    .update();
            if (success) {
                stringRedisTemplate.opsForZSet().add(key, userId, System.currentTimeMillis());
            }
        } else {
            boolean success = update()
                    .setSql("liked = liked - 1")
                    .eq("id", id)
                    .gt("liked", 0)
                    .update();
            if (success) {
                stringRedisTemplate.opsForZSet().remove(key, userId);
            }
        }
        return Result.ok();
    }

    @Override
    public Result queryBlogLikes(Long id) {
        // ZSet 按点赞时间从早到晚取前 5 个用户，再按 Redis 返回顺序组装用户信息。
        String key = RedisConstants.BLOG_LIKED_KEY + id;
        Set<String> top5 = stringRedisTemplate.opsForZSet().range(key, 0, 4);
        if (CollUtil.isEmpty(top5)) {
            return Result.ok(Collections.emptyList());
        }

        List<Long> ids = top5.stream().map(Long::valueOf).collect(Collectors.toList());
        List<User> users = userService.listByIds(ids);
        Map<Long, User> userMap = users.stream().collect(Collectors.toMap(User::getId, Function.identity()));
        List<UserDTO> userDTOS = ids.stream()
                .map(userMap::get)
                .filter(user -> user != null)
                .map(user -> BeanUtil.copyProperties(user, UserDTO.class))
                .collect(Collectors.toList());
        return Result.ok(userDTOS);
    }

    @Override
    public Result queryMyBlog(Integer current) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }
        Page<Blog> page = query()
                .eq("user_id", user.getId())
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(this::isBlogLiked);
        return Result.ok(records);
    }

    @Override
    public Result queryHotBlog(Integer current) {
        Page<Blog> page = query()
                .orderByDesc("liked")
                .page(new Page<>(current, SystemConstants.MAX_PAGE_SIZE));
        List<Blog> records = page.getRecords();
        records.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });
        return Result.ok(records);
    }

    @Override
    public Result queryBlogOfFollow(Long lastId, Integer offset) {
        // Feed 流使用 maxTime + offset 的滚动分页，解决同一毫秒多条数据导致的翻页重复/遗漏。
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            return Result.fail("请先登录");
        }

        String key = RedisConstants.FEED_KEY + user.getId();
        int pageSize = SystemConstants.MAX_PAGE_SIZE;
        Set<ZSetOperations.TypedTuple<String>> typedTuples = stringRedisTemplate.opsForZSet()
                .reverseRangeByScoreWithScores(key, 0, lastId, offset, pageSize);
        if (CollUtil.isEmpty(typedTuples)) {
            ScrollResult result = new ScrollResult();
            result.setList(Collections.emptyList());
            result.setMinTime(0L);
            result.setOffset(0);
            return Result.ok(result);
        }

        List<Long> ids = new ArrayList<>(typedTuples.size());
        long minTime = 0;
        int nextOffset = 1;
        for (ZSetOperations.TypedTuple<String> tuple : typedTuples) {
            ids.add(Long.valueOf(tuple.getValue()));
            long time = tuple.getScore().longValue();
            if (time == minTime) {
                nextOffset++;
            } else {
                minTime = time;
                nextOffset = 1;
            }
        }

        List<Blog> blogs = listByIds(ids);
        Map<Long, Blog> blogMap = blogs.stream().collect(Collectors.toMap(Blog::getId, Function.identity()));
        // listByIds 不保证顺序，必须按 ZSet 查询出的 ID 顺序重排。
        blogs = ids.stream()
                .map(blogMap::get)
                .filter(blog -> blog != null)
                .collect(Collectors.toList());
        blogs.forEach(blog -> {
            queryBlogUser(blog);
            isBlogLiked(blog);
        });

        ScrollResult result = new ScrollResult();
        result.setList(blogs);
        result.setMinTime(minTime);
        result.setOffset(nextOffset);
        return Result.ok(result);
    }

    private void queryBlogUser(Blog blog) {
        User user = userService.getById(blog.getUserId());
        if (user == null) {
            return;
        }
        blog.setName(user.getNickName());
        blog.setIcon(user.getIcon());
    }

    private void isBlogLiked(Blog blog) {
        UserDTO user = UserHolder.getUser();
        if (user == null) {
            blog.setIsLike(false);
            return;
        }
        String key = RedisConstants.BLOG_LIKED_KEY + blog.getId();
        Double score = stringRedisTemplate.opsForZSet().score(key, user.getId().toString());
        blog.setIsLike(score != null);
    }
}
