package com.bing.glmall.seckill.service.impl;

import com.alibaba.csp.sentinel.Entry;
import com.alibaba.csp.sentinel.SphU;
import com.alibaba.csp.sentinel.annotation.SentinelResource;
import com.alibaba.csp.sentinel.slots.block.BlockException;
import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.TypeReference;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.bing.glmall.seckill.feign.CouponFeignService;
import com.bing.glmall.seckill.feign.ProductFeignService;
import com.bing.glmall.seckill.interceptor.LoginUserInterceptor;
import com.bing.glmall.seckill.service.SeckillService;
import com.binggr.common.to.SeckillOrderTo;
import com.bing.glmall.seckill.to.SeckillSkuRedisTo;
import com.bing.glmall.seckill.vo.SeckillSessionWithSkusVo;
import com.bing.glmall.seckill.vo.SkuInfoVo;
import com.binggr.common.utils.R;
import com.binggr.common.vo.MemberRespVo;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang.StringUtils;
import org.redisson.api.RSemaphore;
import org.redisson.api.RedissonClient;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.BeanUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.redis.core.BoundHashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;
import java.util.stream.Collectors;


/**
 * @author: bing
 * @date: 2020/12/12 15:09
 */
@Slf4j
@Service
public class SeckillServiceImpl implements SeckillService {

    @Autowired
    RabbitTemplate rabbitTemplate;

    @Autowired
    CouponFeignService couponFeignService;

    @Autowired
    StringRedisTemplate stringRedisTemplate;

    @Autowired
    ProductFeignService productFeignService;

    @Autowired
    RedissonClient redissonClient;

    private final String SESSIONS_CACHE_PREFIX = "seckill:sessions:";
    private final String SKU_CACHE_PREFIX = "seckill:skus:";
    private final String SKU_STOCK_SEMAPHORE = "seckill:stock:";//+???????????????

    @Override
    public void uploadSecKillSkuLatest3Days() {
        //1???????????????????????????????????????
        R session = couponFeignService.getLatest3DaySession();
        if (session.getCode() == 0) {
            //????????????
            List<SeckillSessionWithSkusVo> sessionData = session.getData(new TypeReference<List<SeckillSessionWithSkusVo>>() {
            });
            //?????????Redis???
            //1????????????????????????
            saveSessionInfos(sessionData);
            //2????????????????????????????????????
            saveSessionSkuInfos(sessionData);

        }

    }

    public List<SeckillSkuRedisTo> blockHandler(BlockException e){
        log.error("getCurrentSeckillSkusResource ????????????");
        return null;
    }

    /**
     * blockHandler????????????????????????????????????????????????????????????????????????fallback????????????????????????????????????
     *
     * ???????????????????????????????????????
     * @return
     */
    @SentinelResource(value = "getCurrentSeckillSkusResource",blockHandler = "blockHandler")
    @Override
    public List<SeckillSkuRedisTo> getCurrentSeckillSkus() {
        //1????????????????????????????????????????????????
        long time = new Date().getTime();
        try(Entry entry = SphU.entry("seckillSkus")){
            Set<String> keys = stringRedisTemplate.keys(SESSIONS_CACHE_PREFIX + "*");
            for (String key : keys) {
                String replace = key.replace(SESSIONS_CACHE_PREFIX, "");
                String[] s = replace.split("_");
                Long start = Long.parseLong(s[0]);
                long end = Long.parseLong(s[1]);
                if (time >= start && time <= end) {
                    //2??????????????????????????????????????????????????????
                    List<String> range = stringRedisTemplate.opsForList().range(key, -100, 100);
                    BoundHashOperations<String, String, Object> hashOps = stringRedisTemplate.boundHashOps(SKU_CACHE_PREFIX);
                    List<Object> list = hashOps.multiGet(range);
                    if (list != null) {
                        List<SeckillSkuRedisTo> collect = list.stream().map(item -> {
                            SeckillSkuRedisTo skuRedisTo = JSON.parseObject((String) item, SeckillSkuRedisTo.class);
                            //skuRedisTo.setRandomCode(null); ???????????????????????????????????????
                            return skuRedisTo;
                        }).collect(Collectors.toList());

                        return collect;
                    }
                    break;
                }
            }
        }catch (BlockException e){
            log.error("???????????????,{}",e.getMessage());
        }
        return null;
    }

    @Override
    public SeckillSkuRedisTo getSkuSeckillInfo(Long skuId) {
        //1?????????????????????????????????????????????key
        BoundHashOperations<String, String, String> hashOps = stringRedisTemplate.boundHashOps(SKU_CACHE_PREFIX);
        Set<String> keys = hashOps.keys();
        String regex = "\\d_" + skuId;
        if (keys != null) {
            for (String key : keys) {
                //2_18
                if (Pattern.matches(regex, key)) {
                    String json = hashOps.get(key);
                    SeckillSkuRedisTo skuRedisTo = JSON.parseObject(json, SeckillSkuRedisTo.class);
                    //?????????
                    long current = new Date().getTime();
                    Long startTime = skuRedisTo.getStartTime();
                    Long endTime = skuRedisTo.getEndTime();
                    if (current >= startTime && current < endTime) {
                    } else {
                        skuRedisTo.setRandomCode(null);
                    }

                    return skuRedisTo;
                }
            }
        }
        return null;
    }

    @Override
    public String kill(String killId, String key, Integer num) {
        MemberRespVo respVo = LoginUserInterceptor.loginUser.get();

        long s1 = System.currentTimeMillis();
        //1???????????????????????????????????????
        BoundHashOperations<String, String, String> hashOps = stringRedisTemplate.boundHashOps(SKU_CACHE_PREFIX);

        String s = hashOps.get(killId);
        //??????redis???????????????
        if (StringUtils.isEmpty(s)) {
            return null;
        } else {
            SeckillSkuRedisTo redis = JSON.parseObject(s, SeckillSkuRedisTo.class);
            //1????????????????????????
            long time = new Date().getTime();
            Long startTime = redis.getStartTime();
            Long endTime = redis.getEndTime();
            long ttl = endTime - startTime;

            if (time >= startTime && time <= endTime) {
                //2???????????????????????????ID
                String randomCode = redis.getRandomCode();
                String skuId = redis.getPromotionSessionId() + "_" + redis.getSkuId();
                if (randomCode.equals(key) && skuId.equals(killId)) {
                    //3????????????????????????????????????
                    if (num <= redis.getSeckillLimit()) {
                        //4????????????????????????????????????????????????????????????????????????redis???????????????userId_sessionId_skuId
                        //SETNX
                        String redisKey = respVo.getId() + "_" + skuId;
                        //????????????
                        Boolean aBoolean = stringRedisTemplate.opsForValue().setIfAbsent(redisKey, num.toString(), ttl, TimeUnit.MILLISECONDS);
                        if (aBoolean) {
                            //????????????????????????????????????
                            RSemaphore semaphore = redissonClient.getSemaphore(SKU_STOCK_SEMAPHORE + randomCode);
                            boolean b = semaphore.tryAcquire(num);
                            if (b) {
                                //????????????;
                                //?????????????????????MQ?????? 10ms
                                String timeId = IdWorker.getTimeId();
                                SeckillOrderTo seckillOrderTo = new SeckillOrderTo();
                                seckillOrderTo.setOrderSn(timeId);
                                seckillOrderTo.setMemberId(respVo.getId());
                                seckillOrderTo.setNum(num);
                                seckillOrderTo.setPromotionSessionId(redis.getPromotionSessionId());
                                seckillOrderTo.setSkuId(redis.getSkuId());
                                seckillOrderTo.setSeckillPrice(redis.getSeckillPrice());
                                rabbitTemplate.convertAndSend("order-event-exchange", "order.seckill.order", seckillOrderTo);
                                long s2 = System.currentTimeMillis();
                                log.info("??????...",(s2-s1));
                                return timeId;
                            }
                            return null;
                        } else {
                            //??????????????????
                            return null;
                        }
                    }
                } else {
                    return null;
                }
            } else {
                return null;
            }

        }

        return null;
    }

    private void saveSessionInfos(List<SeckillSessionWithSkusVo> sessionWithSkusVo) {
        sessionWithSkusVo.stream().forEach(session -> {
            Long startTime = session.getStartTime().getTime();
            Long endTime = session.getEndTime().getTime();
            String key = SESSIONS_CACHE_PREFIX + startTime + "_" + endTime;
            Boolean hasKey = stringRedisTemplate.hasKey(key);
            if (!hasKey) {
                List<String> collect = session.getRelationEntities().stream().map(item -> item.getPromotionSessionId().toString() + "_" + item.getSkuId().toString()).collect(Collectors.toList());
                //??????????????????
                stringRedisTemplate.opsForList().leftPushAll(key, collect);
            }
        });
    }

    private void saveSessionSkuInfos(List<SeckillSessionWithSkusVo> sessionWithSkusVo) {
        sessionWithSkusVo.stream().forEach(session -> {
            //??????hash??????
            BoundHashOperations<String, Object, Object> ops = stringRedisTemplate.boundHashOps(SKU_CACHE_PREFIX);
            session.getRelationEntities().forEach(seckillSkuVo -> {
                //4?????????????????????
                String token = UUID.randomUUID().toString().replace("-", "");

                if (!ops.hasKey(seckillSkuVo.getPromotionSessionId().toString() + "_" + seckillSkuVo.getSkuId().toString())) {
                    //????????????
                    SeckillSkuRedisTo skuRedisTo = new SeckillSkuRedisTo();
                    //1???sku???????????????
                    R skuInfo = productFeignService.getSkuInfo(seckillSkuVo.getSkuId());
                    if (skuInfo.getCode() == 0) {
                        SkuInfoVo info = skuInfo.getData("skuInfo", new TypeReference<SkuInfoVo>() {
                        });
                        skuRedisTo.setSkuInfoVo(info);
                    }

                    //2???sku????????????
                    BeanUtils.copyProperties(seckillSkuVo, skuRedisTo);

                    //3??????????????????????????????????????????
                    skuRedisTo.setStartTime(session.getStartTime().getTime());
                    skuRedisTo.setEndTime(session.getEndTime().getTime());

                    //4?????????????????????
                    skuRedisTo.setRandomCode(token);

                    String s = JSON.toJSONString(skuRedisTo);
                    ops.put(seckillSkuVo.getPromotionSessionId().toString() + "_" + seckillSkuVo.getSkuId().toString(), s);

                    //??????????????????????????????????????????????????????????????????
                    Boolean hasKey = stringRedisTemplate.hasKey(SKU_STOCK_SEMAPHORE + token);
                    //5?????????????????????????????????????????? ?????????
                    RSemaphore semaphore = redissonClient.getSemaphore(SKU_STOCK_SEMAPHORE + token);
                    //??????????????????????????????????????????
                    semaphore.trySetPermits(seckillSkuVo.getSeckillCount());
                }

            });
        });
    }

}
