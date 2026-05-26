package com.hmdp.controller;

import com.hmdp.dto.Result;
import com.hmdp.service.IFollowService;
import org.springframework.web.bind.annotation.*;

import javax.annotation.Resource;

@RestController
@RequestMapping("/follow")
public class FollowController {

    @Resource
    private IFollowService followService;

    @PutMapping("/{followUserId}/{isFollow}")
    public Result follow(@PathVariable("followUserId") Long followUserId, @PathVariable("isFollow") Boolean isFollow) {
        return followService.follow(followUserId, isFollow);
    }

    @GetMapping("/or/not/{followUserId}")
    public Result isFollow(@PathVariable("followUserId") Long followUserId) {
        return followService.isFollow(followUserId);
    }

    @GetMapping("/common/{otherUserId}")
    public Result followCommons(@PathVariable("otherUserId") Long otherUserId) {
        return followService.followCommons(otherUserId);
    }
}
