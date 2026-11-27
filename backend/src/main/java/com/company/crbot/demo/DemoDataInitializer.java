package com.company.crbot.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.annotation.Order;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import java.util.List;
import java.util.Map;

/** 仅显式开启时导入虚构报告，不调用 GitLab 或模型。 */
@Component
@Order(4)
@ConditionalOnProperty(name = "crbot.demo-enabled", havingValue = "true")
public class DemoDataInitializer implements ApplicationRunner {
    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    public DemoDataInitializer(JdbcTemplate jdbc, ObjectMapper mapper) { this.jdbc = jdbc; this.mapper = mapper; }
    @Override
    @Transactional
    public void run(ApplicationArguments args) throws Exception {
        // 数据库事务锁保证多个启动进程不会重复导入。
        jdbc.execute("SELECT pg_advisory_xact_lock(90261127)");
        if (jdbc.queryForObject("SELECT COUNT(*) FROM review_job WHERE project_path = 'demo/order-service'", Long.class) > 0) return;
        jdbc.update("INSERT INTO repo_subscription(gitlab_project_id, project_path, branch_pattern, enabled) VALUES (900001, 'demo/order-service', 'main', false)");
        for (int i = 0; i < 3; i++) {
            boolean issue = i == 0;
            String summary = issue ? "[DEMO] 订单取消缺少状态校验，可能重复退款。此报告为预置示例。" : "[DEMO] 本次示例变更未发现问题；此结论不是模型实际评审结果。";
            var finding = Map.of("category", "business", "severity", "high", "title", "取消订单前缺少状态校验",
                    "filePath", "src/main/java/demo/OrderService.java", "startLine", 12,
                    "detail", "已退款订单仍可再次调用退款接口。", "suggestion", "校验订单状态并使用幂等退款标识。",
                    "existingCode", "refund(order);", "locationStatus", 1);
            var raw = Map.of("summary", summary, "overallSeverity", issue ? "high" : "info",
                    "businessAlignment", Map.of("status", issue ? "unmatched" : "matched", "reason", "演示订单取消规则"),
                    "findings", issue ? List.of(finding) : List.of(),
                    "dimensions", List.of(Map.of("code", "business", "name", "业务相符性", "status", issue ? "unmatched" : "matched", "severity", issue ? "high" : "info", "summary", summary, "findings", issue ? List.of(finding) : List.of())));
            Long jobId = jdbc.queryForObject("""
                INSERT INTO review_job(gitlab_project_id,project_path,ref_branch,event_type,after_sha,title,pusher_name,status,started_at,finished_at)
                VALUES (900001,'demo/order-service','main','push',?,?,'Demo Developer','SUCCESS',NOW() - INTERVAL '30 seconds',NOW()) RETURNING id
                """, Long.class, "demo-" + i, "[DEMO] " + List.of("订单取消与退款", "分页参数校验", "查询性能优化").get(i));
            Long reportId = jdbc.queryForObject("""
                INSERT INTO review_report(job_id,summary,overall_severity,business_status,business_reason,raw_json,markdown,model_name,prompt_version)
                VALUES (?,?,?,?,?,?::jsonb,?,'demo-fixture','demo-v1') RETURNING id
                """, Long.class, jobId, summary, issue ? "high" : "info", issue ? "unmatched" : "matched", "虚构演示数据", mapper.writeValueAsString(raw), summary);
            if (issue) jdbc.update("""
                INSERT INTO review_finding(report_id,category,severity,file_path,start_line,title,detail,suggestion,existing_code,location_status,refs_json)
                VALUES (?,'business','high','src/main/java/demo/OrderService.java',12,?,?,?,?,1,?::jsonb)
                """, reportId, finding.get("title"), finding.get("detail"), finding.get("suggestion"), finding.get("existingCode"), mapper.writeValueAsString(finding));
        }
    }
}
