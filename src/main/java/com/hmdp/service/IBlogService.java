package com.hmdp.service;

import com.hmdp.entity.Blog;
import com.baomidou.mybatisplus.extension.service.IService;
import com.hmdp.dto.Result;

/**
 * <p>
 *  服务类
 * </p>
 *
 * @author 虎哥
 * @since 2021-12-22
 */
public interface IBlogService extends IService<Blog> {

    Result saveBlog(Blog blog);

    Result queryBlogById(Long id);

    Result likeBlog(Long id);

    Result queryBlogLikes(Long id);

    Result queryMyBlog(Integer current);

    Result queryHotBlog(Integer current);

    Result queryBlogOfFollow(Long lastId, Integer offset);
}
