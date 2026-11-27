package com.company.crbot.review;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.annotation.Order;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;

@Component
@Order(3)
public class ReviewWorker implements ApplicationRunner {

    // 不同项目同时评审的上限。同一个项目不会占两个名额
    private static final int WORKERS = 4;

    private final ReviewJobService jobService;
    private final ReviewAgent reviewAgent;
    private final Semaphore slots = new Semaphore(WORKERS);
    private final ExecutorService executor = Executors.newFixedThreadPool(WORKERS, runnable -> {
        Thread thread = new Thread(runnable, "crbot-review");
        thread.setDaemon(true);
        return thread;
    });

    public ReviewWorker(ReviewJobService jobService, ReviewAgent reviewAgent) {
        this.jobService = jobService;
        this.reviewAgent = reviewAgent;
    }

    @Override
    public void run(ApplicationArguments args) {
        // Flyway 完成后再恢复上次中断的任务
        jobService.requeueRunning();
    }

    @Scheduled(fixedDelay = 2000)
    public void poll() {
        // 每个空位领一个任务。同一项目正在评审时，领取会跳过它，去拿别的项目
        while (slots.tryAcquire()) {
            Long jobId = jobService.claim();
            if (jobId == null) {
                slots.release();
                return;
            }
            executor.submit(() -> {
                try {
                    reviewAgent.review(jobId);
                } catch (Exception ex) {
                    jobService.failIfRunning(jobId, ex.getMessage());
                } finally {
                    slots.release();
                }
            });
        }
    }
}
