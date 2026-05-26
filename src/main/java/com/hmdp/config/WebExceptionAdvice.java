package com.hmdp.config;

import com.hmdp.dto.Result;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.sql.SQLNonTransientConnectionException;

@Slf4j
@RestControllerAdvice
public class WebExceptionAdvice {

    @ExceptionHandler(RedisConnectionFailureException.class)
    public Result handleRedisConnectionFailureException(RedisConnectionFailureException e) {
        log.error("Redis connection failure", e);
        return Result.fail("Redis service unavailable");
    }

    @ExceptionHandler({
            SQLNonTransientConnectionException.class,
            DataAccessResourceFailureException.class
    })
    public Result handleDataAccessException(Exception e) {
        log.error("Data access failure", e);
        return Result.fail("Database service unavailable");
    }

    @ExceptionHandler(RuntimeException.class)
    public Result handleRuntimeException(RuntimeException e) {
        log.error("Unhandled runtime exception", e);
        return Result.fail("Server exception");
    }

    @ExceptionHandler(Exception.class)
    public Result handleException(Exception e) {
        log.error("Unhandled exception", e);
        return Result.fail("Server exception");
    }
}
