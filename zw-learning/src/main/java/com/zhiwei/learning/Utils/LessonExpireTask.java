package com.zhiwei.learning.Utils;

import com.zhiwei.learning.service.ILearningLessonService;
import lombok.RequiredArgsConstructor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class LessonExpireTask {
    private final ILearningLessonService learningLessonService;


    @Scheduled(cron = "0 0 1 * * ?")
    public void checkAndUpdateExpiredLessons() {
        System.out.println("开始执行课程过期定时任务...");
        learningLessonService.updateExpiredLessonsStatus();
        System.out.println("课程过期定时任务执行完成");
    }
}