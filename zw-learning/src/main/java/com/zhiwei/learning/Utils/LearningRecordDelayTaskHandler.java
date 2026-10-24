package com.zhiwei.learning.Utils;

import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.zhiwei.common.utils.JsonUtils;
import com.zhiwei.common.utils.StringUtils;
import com.zhiwei.learning.domian.po.LearningLesson;
import com.zhiwei.learning.domian.po.LearningRecord;
import com.zhiwei.learning.mapper.LearningRecordMapper;
import com.zhiwei.learning.service.ILearningLessonService;
import lombok.Data;
import lombok.NoArgsConstructor;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RBlockingQueue;
import org.redisson.api.RDelayedQueue;
import org.redisson.api.RedissonClient;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;

import javax.annotation.PostConstruct;
import javax.annotation.PreDestroy;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 学习记录延时任务处理器
 * <p>
 * 核心设计：Redis Hash 合并写 + Redisson 延迟队列 20 秒异步回写，配合 moment 比对去重，
 * 将视频进度高频上报（15 秒/次）的写库频率降低约 60 倍。
 * <p>
 * 架构：
 * <pre>
 *   前端 15 秒上报 → writeRecordCache（Redis Hash 覆盖写）
 *                  → delayedQueue.offer（Redis ZSET，20 秒后到期）
 *                  → handleDelayTask（到期后 moment 比对去重，仅最后一次落库）
 * </pre>
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class LearningRecordDelayTaskHandler {
    private final RedissonClient redissonClient;
    private final LearningRecordMapper learningRecordMapper;
    private final ILearningLessonService learningLessonService;
    private final StringRedisTemplate redisTemplate;
    private final TransactionTemplate transactionTemplate;

    private static final String LEARNING_RECORD_TEMPLATE = "learning_record:{}";
    private static final String LEARNING_DELAY_QUEUE = "learning:record:delay:queue";

    private RBlockingQueue<LearningRecord> blockingQueue; // 生产者：用 delayedQueue 投递延时任务, 延迟队列，底层 Redis ZSET（score=到期时间戳）+ LIST（到期后转移）
    private RDelayedQueue<LearningRecord> delayedQueue;// 消费者：用 blockingQueue 阻塞等待到期任务，到期后从 delayedQueue 取任务
    private ThreadPoolExecutor delayThreadPool;
    private volatile boolean running = true;

    /**
     * 初始化延时队列和消费线程池
     * <p>
     * 流程：
     * <ol>
     *   <li>创建 Redisson 延迟队列：底层 Redis ZSET（score=到期时间戳）+ LIST（到期后转移）</li>
     *   <li>创建固定大小线程池，线程数 = CPU 核数，消费线程死循环从 queue 中 BLPOP 取任务</li>
     * </ol>
     */
    @PostConstruct
    public void init() {
        // 1. 创建 Redisson 延迟队列
        blockingQueue = redissonClient.getBlockingQueue(LEARNING_DELAY_QUEUE);// 延迟队列，底层 Redis ZSET（score=到期时间戳）+ LIST（到期后转移）
        delayedQueue = redissonClient.getDelayedQueue(blockingQueue);// 延迟队列，底层 Redis ZSET（score=到期时间戳）+ LIST（到期后转移）

        // 2. 创建固定大小线程池，线程数 = CPU 核数
        int coreSize = Runtime.getRuntime().availableProcessors();
        ThreadFactory threadFactory = new ThreadFactory() {
            private final AtomicInteger counter = new AtomicInteger(1);
            @Override
            public Thread newThread(Runnable r) {
                Thread t = new Thread(r, "delay-record-consumer-" + counter.getAndIncrement());
                t.setDaemon(false);
                return t;
            }
        };
        delayThreadPool = new ThreadPoolExecutor(
                coreSize, coreSize,// 固定大小线程池，线程数 = CPU 核数
                0L, TimeUnit.MILLISECONDS,
                new LinkedBlockingQueue<>(),
                threadFactory);

        // 3. 启动消费线程，死循环阻塞等待延迟任务到期
        for (int i = 0; i < coreSize; i++) {
            delayThreadPool.execute(this::handleDelayTask);
        }
        log.info("Redisson学习记录延时队列初始化完成，消费线程数：{}", coreSize);
    }

    /**
     * 优雅关闭：停止消费线程 + 销毁延迟队列
     * <p>
     * 流程：
     * <ol>
     *   <li>设置 running=false，消费线程退出 while 循环</li>
     *   <li>shutdown() + awaitTermination(10s) 等待线程池优雅关闭</li>
     *   <li>超时则 shutdownNow() 强制中断</li>
     *   <li>销毁 Redisson 延迟队列，释放 ZSET + LIST 的 Redis 资源</li>
     * </ol>
     */
    @PreDestroy
    public void destroy() {
        // 1. 通知消费线程停止
        running = false;
        // 2. 优雅关闭线程池
        if (delayThreadPool != null) {
            delayThreadPool.shutdown();
            try {
                if (!delayThreadPool.awaitTermination(10, TimeUnit.SECONDS)) {
                    delayThreadPool.shutdownNow();
                }
            } catch (InterruptedException e) {
                delayThreadPool.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        // 3. 销毁 Redisson 延迟队列
        if (delayedQueue != null) {
            delayedQueue.destroy();
        }
        log.debug("延时任务已结束，资源已释放");
    }

    /**
     * 延迟任务消费循环（死循环，运行在独立线程中）
     * <p>
     * 流程：
     * <ol>
     *   <li>blockingQueue.take() 阻塞等待延迟任务到期（底层 Redis BLPOP）</li>
     *   <li>从 Redis Hash 读取最新的 moment</li>
     *   <li>moment 比对去重：不一致说明有更新上报覆盖了 Redis，丢弃本次任务</li>
     *   <li>moment 一致说明是最后一次上报，执行 DB 落库并清理缓存</li>
     * </ol>
     * 容错：Redis 缓存过期（null）→ 跳过；moment 不匹配 → 跳过；DB 异常 → 日志记录，下次上报重试。
     */
    public void handleDelayTask() {
        while (running && !Thread.currentThread().isInterrupted()) {
            try {
                // 1. 阻塞等待延迟任务到期（底层 Redis BLPOP）
                LearningRecord recordTask = blockingQueue.take();
                // 2. 从 Redis Hash 读取最新 moment（可能已被后续上报覆盖）
                RecordCacheData cacheData = readCacheData(recordTask.getLessonId(), recordTask.getSectionId());
                // 2.1 缓存已过期，跳过
                if (cacheData == null) {
                    continue;
                }
                // 3. 去重核心：moment 不一致说明有更新上报覆盖了 Redis，丢弃本次任务
                if (!Objects.equals(cacheData.getMoment(), recordTask.getMoment())) {
                    continue;
                }
                // 4. moment 一致，这是最后一次上报，执行 DB 落库
                updateRecordAndLesson(recordTask.getLessonId(), recordTask.getSectionId(), cacheData);
                // 5. 清理 Redis 缓存
                clearRecordCache(recordTask.getLessonId(), recordTask.getSectionId());
            } catch (InterruptedException e) {
                log.info("延时任务消费线程被中断");
                break;
            } catch (Exception e) {
                log.error("处理学习记录延时任务发生异常", e);
            }
        }
    }

    /**
     * 添加延时任务：写 Redis Hash 缓存 + 加入延迟队列
     * <p>
     * 每次视频进度上报（非首次且未完成）都会调用此方法。
     * Redis Hash 同一个 field 覆盖写，实现"合并写"效果：15 秒内多次上报，Redis 中始终只有最新一条。
     *
     * @param record 学习记录，包含 id、lessonId、sectionId、moment、finished
     */
    public void addLearningRecordTask(LearningRecord record) {
        // ⚠️ 「上报时刻」必须在这里取，而不是落库时取。
        // 延迟任务是 20 秒后才落库的，落库时刻取决于线程调度，跟用户实际观看顺序没有关系；
        // 用它去比较「谁更新」会把落点写错（见 updateRecordAndLesson 的注释）。
        writeRecordCache(record, System.currentTimeMillis());
        // 2. 加入 Redisson 延迟队列，20 秒后到期
        delayedQueue.offer(record, 20, TimeUnit.SECONDS);
    }

    /**
     * 写 Redis Hash 缓存（不带上报时刻，等价于「此刻上报」）
     *
     * @param record 学习记录
     */
    public void writeRecordCache(LearningRecord record) {
        writeRecordCache(record, System.currentTimeMillis());
    }

    /**
     * 写 Redis Hash 缓存
     * <p>
     * 数据结构：
     * <pre>
     *   Key:   learning_record:课表ID
     *   Field: 小节ID
     *   Value: {"id": 记录ID, "moment": 当前秒数, "finished": false, "reportTime": 上报毫秒时间戳}
     *   过期:  1 分钟
     * </pre>
     *
     * @param record     学习记录
     * @param reportTime 上报时刻（epoch 毫秒），落库时用于判断哪一次观看更晚
     */
    public void writeRecordCache(LearningRecord record, Long reportTime) {
        log.debug("更新学习记录的缓存数据");
        try {
            // 1. 序列化为 JSON
            String jsonStr = JsonUtils.toJsonStr(new RecordCacheData(record, reportTime));
            // 2. key = learning_record:{lessonId}
            String key = StringUtils.format(LEARNING_RECORD_TEMPLATE, record.getLessonId());
            // 3. Hash put：同一 field 覆盖写，实现"合并写"
            redisTemplate.opsForHash().put(key, record.getSectionId().toString(), jsonStr);
            // 4. 1 分钟过期兜底，防止内存泄漏
            redisTemplate.expire(key, Duration.ofMinutes(1));
        } catch (Exception e) {
            log.error("更新学习记录的缓存数据失败", e);
        }
    }

    /**
     * 读 Redis Hash 缓存的原始结构（含上报时刻）
     *
     * @param lessonId  课表ID
     * @param sectionId 小节ID
     * @return 缓存数据，不存在返回 null
     */
    public RecordCacheData readCacheData(Long lessonId, Long sectionId) {
        try {
            // 1. key = learning_record:{lessonId}，field = sectionId
            String key = StringUtils.format(LEARNING_RECORD_TEMPLATE, lessonId);
            Object cacheData = redisTemplate.opsForHash().get(key, sectionId.toString());
            if (cacheData == null) {
                return null;
            }
            // 2. 反序列化 JSON → RecordCacheData
            return JsonUtils.toBean(cacheData.toString(), RecordCacheData.class);
        } catch (Exception e) {
            log.error("读取学习记录的缓存数据失败", e);
            return null;
        }
    }

    /**
     * 读 Redis Hash 缓存
     * <p>
     * 从缓存中反序列化最新的学习记录，返回 null 表示缓存已过期或不存在。
     *
     * @param lessonId  课表ID
     * @param sectionId 小节ID
     * @return 学习记录，缓存不存在返回 null
     */
    public LearningRecord readRecordCache(Long lessonId, Long sectionId) {
        RecordCacheData data = readCacheData(lessonId, sectionId);
        if (data == null) {
            return null;
        }
        // 组装 LearningRecord 对象返回
        LearningRecord record = new LearningRecord();
        record.setId(data.getId());
        record.setLessonId(lessonId);
        record.setSectionId(sectionId);
        record.setMoment(data.getMoment());
        record.setFinished(data.getFinished());
        return record;
    }

    /**
     * 清理 Redis Hash 缓存中指定小节的记录
     *
     * @param lessonId  课表ID
     * @param sectionId 小节ID
     */
    public void clearRecordCache(Long lessonId, Long sectionId) {
        try {
            // 1. key = learning_record:{lessonId}，删除 field = sectionId
            String key = StringUtils.format(LEARNING_RECORD_TEMPLATE, lessonId);
            redisTemplate.opsForHash().delete(key, sectionId.toString());
        } catch (Exception e) {
            log.error("清理学习记录的缓存数据失败", e);
        }
    }

    /**
     * 落库：更新学习记录 + 更新课表最新学习信息
     * <p>
     * 事务包裹保证两次 DB 写的原子性：
     * <ol>
     *   <li>更新 learning_record 表：moment、finished</li>
     *   <li>更新 learning_lesson 表：latestSectionId、latestLearnTime</li>
     * </ol>
     * ⚠️ 第 2 步必须带条件：消费线程池大小 = CPU 核数，多个小节的延迟任务会**并发**落库，
     * 先后顺序完全取决于线程调度，跟用户实际观看顺序无关。原来是无条件 set，
     * 于是「用户从 A 切到 B」时，A 的任务只要后落地就会把落点写回 A ——
     * 下次打开课程落在错误的小节上，断点续播直接失效。
     * 现在的比较基准是「上报时刻」（reportTime），只有更晚的一次观看才允许覆盖落点。
     *
     * @param lessonId  课表ID
     * @param sectionId 小节ID
     * @param data      缓存中最新的上报快照（moment、finished、reportTime）
     */
    private void updateRecordAndLesson(Long lessonId, Long sectionId, RecordCacheData data) {
        // 上报时刻：缓存里没有（老数据/查询回写的缓存）就退化成当前时间
        LocalDateTime reportTime = toDateTime(data.getReportTime());
        // 事务包裹，保证两次 DB 更新的原子性
        transactionTemplate.executeWithoutResult(status -> {
            // 1. 更新 learning_record 表：moment、finished（按小节定位，各小节互不影响）
            LambdaUpdateWrapper<LearningRecord> wrapper = new LambdaUpdateWrapper<>();
            wrapper.eq(LearningRecord::getLessonId, lessonId)
                    .eq(LearningRecord::getSectionId, sectionId);

            LearningRecord updateRecord = new LearningRecord();
            updateRecord.setMoment(data.getMoment());
            updateRecord.setFinished(data.getFinished());
            learningRecordMapper.update(updateRecord, wrapper);

            // 2. 更新 learning_lesson 表：最新学习小节、最近学习时间
            //    条件 = 课表还没有学习时间，或已有时间不晚于本次上报时刻
            //    （同一秒内的两次上报用 <= 放行，后到者覆盖，保证「最后一次观看」生效）
            LambdaUpdateWrapper<LearningLesson> lessonWrapper = new LambdaUpdateWrapper<>();
            lessonWrapper.set(LearningLesson::getLatestSectionId, sectionId)
                    .set(LearningLesson::getLatestLearnTime, reportTime)
                    .eq(LearningLesson::getId, lessonId)
                    .and(w -> w.isNull(LearningLesson::getLatestLearnTime)
                            .or().le(LearningLesson::getLatestLearnTime, reportTime));
            learningLessonService.update(lessonWrapper);
        });
    }

    /**
     * epoch 毫秒 → LocalDateTime（截到秒）
     * <p>
     * learning_lesson.latest_learn_time 是 datetime（秒精度），带小数传下去会被 MySQL 四舍五入，
     * 可能把时间往前推一秒，导致「本该生效的上报被判成过期」。这里先自己截断。
     */
    private static LocalDateTime toDateTime(Long epochMilli) {
        long ms = epochMilli == null ? System.currentTimeMillis() : epochMilli;
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(ms), ZoneId.systemDefault()).withNano(0);
    }

    /**
     * Redis 缓存中存储的数据结构
     * <p>
     * 只存储落库所需的最小字段：id、moment、finished，外加「上报时刻」reportTime。
     */
    @Data
    @NoArgsConstructor
    public static class RecordCacheData {
        private Long id;
        private Integer moment;
        private Boolean finished;
        /**
         * 上报时刻（epoch 毫秒）。
         * <p>
         * 为什么不用落库时刻：延迟任务 20 秒后才落库，落库时刻由线程调度决定，
         * 跟用户观看顺序无关，拿它比较会把课表的 latest_section_id 写错。
         */
        private Long reportTime;

        public RecordCacheData(LearningRecord record) {
            this(record, null);
        }

        public RecordCacheData(LearningRecord record, Long reportTime) {
            this.id = record.getId();
            this.moment = record.getMoment();
            this.finished = record.getFinished();
            this.reportTime = reportTime;
        }
    }
}