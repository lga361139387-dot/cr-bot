package com.company.crbot.review;

import com.company.crbot.common.PageResult;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/v1/reviews")
public class ReviewController {

    private final ReviewJobService reviewJobService;
    private final ReviewLogService reviewLogService;
    private final FindingAnnotationService annotationService;
    private final ReviewFeedbackChatService feedbackChatService;

    public ReviewController(ReviewJobService reviewJobService, ReviewLogService reviewLogService,
                            FindingAnnotationService annotationService, ReviewFeedbackChatService feedbackChatService) {
        this.reviewJobService = reviewJobService;
        this.reviewLogService = reviewLogService;
        this.annotationService = annotationService;
        this.feedbackChatService = feedbackChatService;
    }

    @GetMapping
    public PageResult<ReviewJobService.ListItem> list(@RequestParam(required = false) Long projectId,
                                                      @RequestParam(required = false) String branch,
                                                      @RequestParam(required = false) String status,
                                                      @RequestParam(required = false) String severity,
                                                      @RequestParam(required = false) String pusher,
                                                      @RequestParam(defaultValue = "1") int page,
                                                      @RequestParam(defaultValue = "20") int size) {
        // 项目按仓库编号精确筛选，空则返回全部评审
        return reviewJobService.list(projectId, branch, status, severity, pusher, page, size);
    }

    @GetMapping("/facets")
    public ReviewJobService.ReviewFacets facets(@RequestParam(required = false) Long projectId,
                                                @RequestParam(required = false) String branch) {
        // 项目页签、分支和提交人下拉都从已有评审记录汇总
        return reviewJobService.facets(projectId, branch);
    }

    @GetMapping("/stats")
    public ReviewJobService.Stats stats() {
        return reviewJobService.stats();
    }

    @GetMapping("/{id}")
    public ReviewJobService.Detail detail(@PathVariable Long id) {
        return reviewJobService.detail(id);
    }

    @GetMapping("/{id}/logs")
    public java.util.List<ReviewLogService.Row> logs(@PathVariable Long id) {
        // 先确认评审存在，再返回这次的过程日志
        reviewJobService.detail(id);
        return reviewLogService.list(id);
    }

    @PostMapping("/{id}/retry")
    public void retry(@PathVariable Long id) {
        reviewJobService.retry(id);
    }

    @GetMapping("/{id}/annotations")
    public java.util.List<FindingAnnotationService.AnnotationView> annotations(@PathVariable Long id) {
        return annotationService.history(id);
    }

    @PostMapping("/{id}/annotations")
    public FindingAnnotationService.AnnotationView annotate(@PathVariable Long id,
                                                            @RequestBody FindingAnnotationService.SaveRequest request) {
        return annotationService.save(id, request.label(), request.remark());
    }

    @GetMapping("/{id}/findings/{findingId}/annotations")
    public java.util.List<FindingAnnotationService.AnnotationView> annotationHistory(@PathVariable Long id,
                                                                                     @PathVariable Long findingId) {
        return annotationService.history(id, findingId);
    }

    @PostMapping("/{id}/findings/{findingId}/annotations")
    public FindingAnnotationService.AnnotationView annotate(@PathVariable Long id, @PathVariable Long findingId,
                                                            @RequestBody FindingAnnotationService.SaveRequest request) {
        return annotationService.save(id, findingId, request.label(), request.remark());
    }

    @PostMapping("/{id}/rereview")
    public void rereview(@PathVariable Long id) {
        reviewJobService.rereview(id);
    }

    @GetMapping("/{id}/feedback-chat")
    public java.util.List<ReviewFeedbackChatService.Message> chat(@PathVariable Long id) {
        return feedbackChatService.list(id);
    }

    @PostMapping("/{id}/feedback-chat")
    public java.util.List<ReviewFeedbackChatService.Message> talk(@PathVariable Long id,
                                                                  @RequestBody ReviewFeedbackChatService.TalkRequest request) {
        return feedbackChatService.talk(id, request.content());
    }

    @PostMapping("/{id}/feedback-chat/confirm")
    public java.util.List<ReviewFeedbackChatService.Message> confirmChat(@PathVariable Long id) {
        return feedbackChatService.confirm(id);
    }

    @PostMapping("/{id}/void")
    public void voidReview(@PathVariable Long id) {
        reviewJobService.voidReview(id);
    }
}
