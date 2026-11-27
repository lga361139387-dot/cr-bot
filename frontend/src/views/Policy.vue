<template>
  <div>
    <div class="section-head">
      <div>
        <h2>策略说明</h2>
      </div>
    </div>
    <div class="grid grid-2">
      <div class="card">
        <div class="card-title">级别 → 展示</div>
        <div class="settings-list">
          <div class="branch-row">
            <div class="left">
              <span class="badge badge-info">提示</span>
              <span class="desc">说明性发现，不影响阅读优先级</span>
            </div>
            <span class="badge badge-done">出报告</span>
          </div>
          <div class="branch-row">
            <div class="left">
              <span class="badge badge-low">低</span>
              <span class="desc">风格、可维护性等轻微问题。不符合编码规范不算低</span>
            </div>
            <span class="badge badge-done">出报告</span>
          </div>
          <div class="branch-row">
            <div class="left">
              <span class="badge badge-medium">中</span>
              <span class="desc">空指针、事务边界、权限校验缺失等</span>
            </div>
            <span class="badge badge-done">出报告</span>
          </div>
          <div class="branch-row">
            <div class="left">
              <span class="badge badge-high">高危</span>
              <span class="desc">不符合编码规范、性能问题，以及注入、硬编码密钥、越权</span>
            </div>
            <span class="badge badge-done">出报告</span>
          </div>
        </div>
      </div>
      <div class="card">
        <div class="card-title">技术链路</div>
        <div class="biz-text">
          <p><strong>1. 提交先落库。</strong>开发者 <code>git push</code> 或更新 Merge Request 时，GitLab 先接受变更。</p>
          <p><strong>2. Webhook。</strong>GitLab 把 Push / MR 事件发给 CRBOT。密钥不对直接拒绝；分支没订阅则不建任务。</p>
          <p><strong>3. 异步评审。</strong>任务拉取 diff，检索编码规范和知识库。只有字段变更或复杂 SQL 才会查只读库的表结构和索引；没配数据源就跳过查库，评审继续，不会整单失败。</p>
          <p><strong>4. 看板。</strong>人在这里看历史、订阅和报告。失败任务可以重新排队。对单条缺陷的标注默认只保存，要点「重评审」才会把备注带进下一轮。知识库上传可先预审疑点，确认后仍按原文入库。</p>
          <p><strong>5. 预留。</strong>质量看板、迭代交付清单、合并门禁。</p>
        </div>
      </div>
    </div>
  </div>
</template>
