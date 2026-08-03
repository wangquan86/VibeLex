const find = (selector) => document.querySelector(selector);

const escapeHtml = (value) =>
  String(value ?? "")
    .replaceAll("&", "&amp;")
    .replaceAll("<", "&lt;")
    .replaceAll(">", "&gt;")
    .replaceAll('"', "&quot;")
    .replaceAll("'", "&#039;");

function formatDateTime(value) {
  const text = String(value ?? "").trim();
  if (!text) return "—";
  const match = text.match(/^(\d{4}-\d{2}-\d{2})[T ](\d{2}:\d{2}:\d{2})/);
  return match ? `${match[1]} ${match[2]}` : text;
}

function safeExternalUrl(value) {
  try {
    const url = new URL(String(value || ""));
    return ["http:", "https:"].includes(url.protocol) ? escapeHtml(url.href) : "";
  } catch {
    return "";
  }
}

const ENUM_LABELS = {
  approved: "已批准",
  rejected: "已驳回",
  expired: "已过期",
  succeeded: "成功",
  partial_success: "部分成功",
  failed: "失败",
  cancelled: "已停止",
  running: "运行中",
  idle: "空闲",
  planning: "规划中",
  partial: "部分失败",
  retry_wait: "等待重试",
  duplicate: "重复跳过",
  imported: "已导入",
  ignored: "已忽略",
  full: "全量",
  incremental: "增量",
  editing: "编辑中",
  pending: "待处理",
  processing: "处理中",
  parse: "解析",
  deduplicate: "判重",
  ai_enrichment: "AI 丰富化",
  candidate_creation: "创建候选",
  UPSERT: "写入/更新",
  DELETE: "删除",
  pending_review: "审核中",
  returned: "已退回",
  published: "已发布",
  archived: "已归档",
  manual: "人工录入",
  import: "文件导入",
  none: "无风险",
  low: "低风险",
  medium: "中风险",
  high: "高风险",
  restricted: "受限",
  untracked: "未跟踪",
  emerging: "新出现",
  growing: "快速增长",
  active: "有效",
  stable: "稳定",
  declining: "热度下降",
  obsolete: "已过时",
  slang: "网络流行语",
  homophone: "谐音表达",
  abbreviation: "缩写",
  template_phrase: "模板句式",
  number_code: "数字暗语",
  emotion_expression: "情绪表达",
  sarcasm: "反讽表达",
  foreign_term: "外来语",
  fandom_term: "饭圈用语",
  game_term: "游戏用语",
  acg_term: "ACG 用语",
  livestream_term: "直播用语",
  workplace_term: "职场用语",
  other: "其他",
  neutral: "中性",
  positive: "正向",
  negative: "负向",
  mixed: "混合",
  informal: "非正式",
  formal: "正式",
  alias: "别名",
  pinyin: "拼音",
  typo_variant: "常见错别字",
  typo: "常见错别字",
  traditional: "繁体变体",
  derived: "衍生词形",
  case_variant: "大小写变体",
  spacing_variant: "空格变体",
  counterexample: "反例",
  exact_match: "精确匹配",
  normalized_match: "归一化匹配",
  pinyin_match: "拼音匹配",
  regex_match: "正则匹配",
  context_score: "上下文评分",
  normal: "普通审核",
  manual_review: "人工复核",
  block: "禁止",
  dictionary: "词典来源",
  trend: "趋势来源",
  explanation: "解释来源",
  community: "社区来源",
  dataset: "数据集来源",
  internal: "内部录入",
  overseas: "海外来源",
  discovery: "发现依据",
  definition: "释义依据",
  origin: "起源依据",
  usage: "用法依据",
  risk: "风险依据",
  variant: "变体依据",
  create: "新建",
  update: "更新",
  rollback: "回滚",
  editorial: "人工编辑",
  imported: "导入生成",
  automatic: "自动生成",
  draft: "草稿",
  converted: "已转换",
  merged: "已合并",
  inactive: "已停用",
  disabled: "已禁用",
  "zh-CN": "简体中文",
};

const enumLabel = (value) => ENUM_LABELS[value] || String(value ?? "—");
const trendLabel = (value) => ({
  untracked: "未跟踪",
  emerging: "新出现",
  growing: "快速增长",
  stable: "长期稳定",
  declining: "热度下降",
})[value] || enumLabel(value);

function currentActor() {
  return find("#actor").value;
}

/**
 * 统一管理 REST 请求与 ProblemDetail 错误处理。
 */
async function api(path, options = {}) {
  const response = await fetch(path, {
    ...options,
    headers: {
      "Content-Type": "application/json",
      "X-Actor-Id": currentActor(),
      ...(options.headers || {}),
    },
  });

  if (!response.ok) {
    let problem;
    try {
      problem = await response.json();
    } catch {
      problem = { detail: await response.text() };
    }
    throw new Error(problem.detail || `HTTP ${response.status}`);
  }

  if (response.status === 204) {
    return null;
  }
  const responseText = await response.text();
  return responseText ? JSON.parse(responseText) : null;
}

let toastTimer;

function showToast(message) {
  const dialog = [...document.querySelectorAll("dialog[open]")].at(-1);
  let toast = find("#toast");
  if (dialog) {
    toast = dialog.querySelector(".dialog-toast");
    if (!toast) {
      toast = document.createElement("div");
      toast.className = "dialog-toast";
      toast.setAttribute("role", "status");
      dialog.append(toast);
    }
  }
  document.querySelectorAll("#toast, .dialog-toast").forEach((item) => {
    item.style.display = "none";
  });
  toast.textContent = message;
  toast.style.display = "block";
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => {
    toast.style.display = "none";
  }, 2800);
}

function statusBadge(status) {
  const success = ["succeeded", "approved", "published", "imported"].includes(status);
  const info = ["running", "pending", "editing", "pending_review", "partial_success"].includes(status);
  const warning = ["returned", "rejected"].includes(status);
  const tone = success ? "success" : info ? "info" : warning ? "warning" : "";
  return `<span class="badge ${tone}">${escapeHtml(enumLabel(status))}</span>`;
}

function candidateSourceLabel(row) {
  return row.source_type === "manual" ? "人工录入" : escapeHtml(row.source_name || "未知来源");
}

function renderTable(rows, columns, actions) {
  if (!rows.length) {
    return '<div class="empty-state">暂无符合条件的数据</div>';
  }

  const head = columns.map(([title]) => `<th>${title}</th>`).join("");
  const body = rows
    .map((row) => {
      const cells = columns
        .map(([, render, className]) => {
          const value = render(row) ?? "";
          return `<td class="${className || ""}">${value}</td>`;
        })
        .join("");
      const actionCell = actions ? `<td>${actions(row)}</td>` : "";
      return `<tr>${cells}${actionCell}</tr>`;
    })
    .join("");

  return `
    <table>
      <thead><tr>${head}${actions ? "<th>操作</th>" : ""}</tr></thead>
      <tbody>${body}</tbody>
    </table>`;
}

function activatePage(pageName) {
  document.querySelectorAll(".sidebar nav button, .page").forEach((element) => {
    element.classList.remove("active");
  });
  document.querySelector(`[data-page="${pageName}"]`).classList.add("active");
  find(`#${pageName}`).classList.add("active");
  document.body.classList.remove("sidebar-open");
  find(".mobile-menu")?.setAttribute("aria-expanded", "false");
  window.scrollTo({ top: 0, behavior: "smooth" });
  loadPage(pageName);
}

document.querySelectorAll(".sidebar nav button").forEach((button) => {
  button.addEventListener("click", () => activatePage(button.dataset.page));
});

document.querySelectorAll("[data-page-link]").forEach((button) => {
  button.addEventListener("click", () => activatePage(button.dataset.pageLink));
});

find(".mobile-menu")?.addEventListener("click", () => {
  const open = document.body.classList.toggle("sidebar-open");
  find(".mobile-menu").setAttribute("aria-expanded", String(open));
});

find(".sidebar-mask")?.addEventListener("click", () => {
  document.body.classList.remove("sidebar-open");
  find(".mobile-menu")?.setAttribute("aria-expanded", "false");
});

const sidebarCollapseButton = find(".sidebar-collapse");
const sidebarNavButtons = [...document.querySelectorAll(".sidebar nav button")];
sidebarNavButtons.forEach((button) => {
  button.title = button.querySelector("span")?.textContent.trim() || "";
});
const setSidebarCollapsed = (collapsed, persist = true) => {
  document.body.classList.toggle("sidebar-collapsed", collapsed);
  sidebarCollapseButton?.setAttribute("aria-expanded", String(!collapsed));
  sidebarCollapseButton?.setAttribute("aria-label", collapsed ? "展开侧边栏" : "收起侧边栏");
  if (persist) {
    try {
      window.localStorage.setItem("vibelex.sidebarCollapsed", String(collapsed));
    } catch {
      // 本地存储不可用不影响侧边栏折叠。
    }
  }
};
if (sidebarCollapseButton) {
  let collapsed = false;
  try {
    collapsed = window.localStorage.getItem("vibelex.sidebarCollapsed") === "true";
  } catch {
    // 本地存储不可用时使用默认展开状态。
  }
  setSidebarCollapsed(collapsed, false);
  sidebarCollapseButton.addEventListener("click", () => {
    setSidebarCollapsed(!document.body.classList.contains("sidebar-collapsed"));
  });
}

async function loadSources() {
  const sources = await api("/api/admin/imports/sources");
  const current = find("#import-source").value;
  find("#import-source").innerHTML = sources
    .map((source) => `<option value="${escapeHtml(source.code)}">${escapeHtml(source.name)}</option>`)
    .join("");
  if (sources.some((source) => source.code === current)) find("#import-source").value = current;
}

async function loadSourceDictionary() {
  const sources = await api("/api/admin/imports/source-dictionary");
  ["#candidate-source", "#review-source", "#entry-source"].forEach((selector) => {
    const select = find(selector);
    const current = select.value;
    select.innerHTML = `<option value="">全部来源</option>${sources
      .map((source) => `<option value="${escapeHtml(source)}">${escapeHtml(source)}</option>`)
      .join("")}`;
    if (sources.includes(current)) select.value = current;
  });
}

async function loadFiles() {
  if (!find("#import-source").options.length) await loadSources();
  const source = find("#import-source").value || "chime";
  const files = await api(`/api/admin/imports/files?source=${encodeURIComponent(source)}`);
  find("#import-file").innerHTML = files
    .map((file) => `<option>${file}</option>`)
    .join("");
}

const importState = { runId: null, page: 1, size: 20, status: "all", query: "" };
let importPollTimer = null;

function importRecordActions(row) {
  const detail = `<button class="table-action" data-import-record-detail="${row.id}">详情</button>`;
  const candidate = row.candidate_id ? `<button class="table-action" data-import-candidate="${row.candidate_id}">查看候选</button>` : "";
  const retry = row.status === "failed" ? `<button class="table-action" data-import-record-retry="${row.id}">重新处理</button>` : "";
  return `${detail}${candidate}${retry}`;
}

function renderImportPagination(data) {
  const host = find("#import-record-pagination");
  if (!data.totalElements) { host.innerHTML = ""; return; }
  const page = Number(data.page) || 1;
  const totalPages = Number(data.totalPages) || 1;
  const size = Number(data.size) || importState.size;
  host.innerHTML = `<div class="pagination-info"><span>第 ${page} / ${totalPages} 页，共 ${data.totalElements} 条</span><label class="pagination-size-field">每页<select id="import-record-size" class="pagination-size">${pageSizeOptions(size)}</select></label></div><div class="pagination-buttons"><button class="page-button" data-import-record-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>‹</button><button class="page-button active">${page} / ${totalPages}</button><button class="page-button" data-import-record-page="${page + 1}" ${page >= totalPages ? "disabled" : ""}>›</button></div>`;
  host.querySelectorAll("[data-import-record-page]").forEach((button) => button.addEventListener("click", () => loadImportRecords(Number(button.dataset.importRecordPage))));
  find("#import-record-size").addEventListener("change", (event) => { importState.size = Number(event.target.value); loadImportRecords(1); });
}

async function loadImportRecords(page = importState.page) {
  if (!importState.runId) return;
  importState.page = page;
  const params = new URLSearchParams({ status: importState.status, query: importState.query, page: String(page), size: String(importState.size) });
  const data = await api(`/api/admin/imports/${importState.runId}/records?${params}`);
  const rows = data.items || [];
  find("#import-records-table").innerHTML = renderTable(rows, [
    ["序号", (row) => row.source_index],
    ["词条", (row) => escapeHtml(row.term_raw)],
    ["处理结果", (row) => statusBadge(row.status === "processing" ? "pending" : row.status)],
    ["处理阶段", (row) => row.processor_stage ? escapeHtml(enumLabel(row.processor_stage)) : "—"],
    ["候选状态", (row) => row.candidate_status ? statusBadge(row.candidate_status) : "—"],
    ["导入时间", (row) => escapeHtml(formatDateTime(row.processed_at))],
    ["重试", (row) => row.attempt_count || 0],
    ["错误摘要", (row) => escapeHtml(row.error_message || "—")],
  ], importRecordActions);
  renderImportPagination(data);
  document.querySelectorAll("[data-import-record-detail]").forEach((button) => button.addEventListener("click", () => showImportRecordDetail(button.dataset.importRecordDetail)));
  document.querySelectorAll("[data-import-candidate]").forEach((button) => button.addEventListener("click", () => showCandidateDetail(button.dataset.importCandidate)));
  document.querySelectorAll("[data-import-record-retry]").forEach((button) => button.addEventListener("click", () => retryImportRecord(button.dataset.importRecordRetry)));
}

async function showImportRecordDetail(recordId) {
  try {
    const row = await api(`/api/admin/imports/${importState.runId}/records/${recordId}`);
    let note = {};
    try { note = typeof row.processing_note === "string" ? JSON.parse(row.processing_note) : (row.processing_note || {}); } catch { note = {}; }
    const examples = Array.isArray(note.examples) ? note.examples : [];
    const references = Array.isArray(note.origin_references) ? note.origin_references.slice(0, 3) : [];
    const ai = note.ai_enrichment || {};
    const duplicateAction = row.duplicate_target_id
      ? row.duplicate_target_type === "candidate"
        ? `<button class="table-action" data-import-duplicate-candidate="${row.duplicate_target_id}">查看重复候选</button>`
        : `<button class="table-action" data-import-duplicate-entry="${row.duplicate_target_id}">查看重复正式词条</button>`
      : "—";
    find("#import-record-detail-content").innerHTML = `
      <dl class="detail-grid">
        <div class="detail-item"><dt>记录 ID</dt><dd>${row.id}</dd></div>
        <div class="detail-item"><dt>处理结果</dt><dd>${statusBadge(row.status === "processing" ? "pending" : row.status)}</dd></div>
        <div class="detail-item"><dt>词条</dt><dd><strong>${escapeHtml(row.term_raw)}</strong></dd></div>
        <div class="detail-item"><dt>处理阶段</dt><dd>${escapeHtml(enumLabel(row.processor_stage || "—"))}</dd></div>
        <div class="detail-item wide"><dt>原始释义</dt><dd class="detail-definition">${escapeHtml(row.definition_raw || "—")}</dd></div>
        <div class="detail-item wide"><dt>词条起源说明</dt><dd class="detail-definition">${escapeHtml(note.origin || "—")}</dd></div>
        <div class="detail-item wide"><dt>起源参考链接</dt><dd>${references.length ? references.map((item) => { const url = safeExternalUrl(item.url); return url ? `<p><a class="evidence-link" href="${url}" target="_blank" rel="noreferrer">${escapeHtml(item.title || item.url)} ↗</a></p>` : ""; }).join("") : "—"}</dd></div>
        <div class="detail-item wide"><dt>使用例句</dt><dd>${renderExampleList(examples, (example) => `<p>${escapeHtml(example)}</p>`)}</dd></div>
        <div class="detail-item"><dt>AI 模型</dt><dd>${escapeHtml(row.ai_model || "—")}</dd></div>
        <div class="detail-item"><dt>置信度</dt><dd>${ai.confidence ?? "—"}</dd></div>
        <div class="detail-item wide"><dt>复核问题</dt><dd>${ai.needs_review ? `<span class="badge warning">需要复核</span> ${escapeHtml((ai.issues || []).join("、"))}` : "—"}</dd></div>
        <div class="detail-item wide"><dt>失败原因</dt><dd class="detail-definition">${escapeHtml(row.error_message || "—")}</dd></div>
        <div class="detail-item wide"><dt>重复目标</dt><dd>${duplicateAction}</dd></div>
      </dl>`;
    find("#import-record-detail-content").querySelector("[data-import-duplicate-candidate]")?.addEventListener("click", (event) => showCandidateDetail(event.currentTarget.dataset.importDuplicateCandidate));
    find("#import-record-detail-content").querySelector("[data-import-duplicate-entry]")?.addEventListener("click", (event) => showEntryDetail(event.currentTarget.dataset.importDuplicateEntry));
    find("#import-record-dialog").showModal();
  } catch (error) { showToast(`词条详情加载失败：${error.message}`); }
}

async function retryImportRecord(recordId) {
  try {
    const result = await api(`/api/admin/imports/${importState.runId}/retry?recordId=${encodeURIComponent(recordId)}`, { method: "POST" });
    showToast(result.retriedCount ? "词条已重新入队" : "当前没有可重试的失败词条");
    await loadImportRecords(importState.page);
    await loadImports();
  } catch (error) { showToast(`重新处理失败：${error.message}`); }
}

async function cancelImportRun(runId) {
  if (!window.confirm("停止后将不再处理新的词条，当前正在处理的词条可能仍会完成。确认停止吗？")) return;
  try {
    await api(`/api/admin/imports/${runId}/cancel`, { method: "POST" });
    showToast("导入任务已停止");
    await loadImports();
    if (String(importState.runId) === String(runId)) await loadImportRecords(importState.page);
  } catch (error) {
    showToast(`停止任务失败：${error.message}`);
  }
}

async function openImportRun(run) {
  importState.runId = run.id;
  importState.page = 1;
  importState.status = "all";
  importState.query = "";
  find("#import-run-detail-title").textContent = `任务 #${run.id}：${run.source_name} / ${run.file_name}`;
  find("#import-record-status").value = "all";
  find("#import-record-query").value = "";
  updateImportRetryButton(run);
  find("#import-run-detail").hidden = false;
  await loadImportRecords(1);
  find("#import-run-detail").scrollIntoView({ behavior: "smooth", block: "start" });
}

function updateImportRetryButton(run) {
  const button = find("#import-retry-failed");
  if (!button) return;
  const failedCount = Number(run?.failed_count) || 0;
  const cancelled = run?.status === "cancelled";
  button.disabled = cancelled || failedCount === 0;
  button.textContent = cancelled ? "任务已停止" : failedCount ? `重试失败词条（${failedCount}）` : "暂无失败词条";
  button.title = cancelled ? "已停止的任务不能重试" : failedCount ? `重新处理 ${failedCount} 条失败词条` : "当前任务没有失败词条";
}

function importRunActions(row) {
  const detail = `<button class="table-action" data-import-run="${row.id}">查看词条</button>`;
  const cancel = row.status === "running"
    ? `<button class="table-action danger-action" data-import-cancel="${row.id}">停止任务</button>`
    : "";
  return `${detail}${cancel}`;
}

async function loadImports() {
  await loadSources();
  await loadFiles();
  const runs = await api("/api/admin/imports");
  if (importState.runId) updateImportRetryButton(runs.find((run) => String(run.id) === String(importState.runId)));
  find("#imports-table").innerHTML = renderTable(runs, [
    ["ID", (row) => row.id],
    ["数据来源", (row) => escapeHtml(row.source_name)],
    ["文件名称", (row) => row.file_name],
    ["来源版本", (row) => row.source_version],
    ["运行状态", (row) => statusBadge(row.status)],
    ["更新时间", (row) => escapeHtml(formatDateTime(row.updated_at))],
    ["总数", (row) => row.total_count],
    ["候选数", (row) => row.candidate_count],
    ["已存在", (row) => row.duplicate_count || 0],
    ["失败", (row) => row.failed_count || 0],
    ["拒绝数", (row) => row.rejected_count],
    ["发起人", (row) => row.initiated_by],
  ], importRunActions);
  document.querySelectorAll("[data-import-run]").forEach((button) => {
    const run = runs.find((item) => String(item.id) === String(button.dataset.importRun));
    button.addEventListener("click", () => run && openImportRun(run));
  });
  document.querySelectorAll("[data-import-cancel]").forEach((button) => {
    button.addEventListener("click", () => cancelImportRun(button.dataset.importCancel));
  });
  const active = runs.some((run) => run.status === "running");
  if (active && !importPollTimer) {
    importPollTimer = window.setInterval(async () => {
      await loadImports();
      if (importState.runId) await loadImportRecords(importState.page);
    }, 3000);
  } else if (!active && importPollTimer) {
    window.clearInterval(importPollTimer);
    importPollTimer = null;
  }
}

const pageSizeOptions = (selected) =>
  [20, 50, 100]
    .map((size) => `<option value="${size}" ${Number(selected) === size ? "selected" : ""}>${size} 条</option>`)
    .join("");

const candidateState = { page: 1, size: 20 };

function candidateActions(row) {
  const detail = `<button class="table-action" data-candidate-detail="${row.id}">查看详情</button>`;
  if (["editing", "returned"].includes(row.status)) {
    return `${detail}<button class="table-action" data-candidate-edit="${row.id}">编辑</button><button class="table-action" data-candidate-submit="${row.id}">提交审核</button>`;
  }
  if (row.status === "published" && row.published_meme_id) {
    return `${detail}<button class="table-action" data-published-entry="${row.published_meme_id}">查看正式词条</button>`;
  }
  return detail;
}

function renderCandidatePagination(pageData) {
  const host = find("#candidate-pagination");
  if (!pageData.totalElements) {
    host.innerHTML = "";
    return;
  }

  const page = Number(pageData.page);
  const totalPages = Number(pageData.totalPages);
  const start = (page - 1) * Number(pageData.size) + 1;
  const end = Math.min(page * Number(pageData.size), Number(pageData.totalElements));
  host.innerHTML = `
    <div class="pagination-info"><span>显示 ${start}–${end} 条，共 ${pageData.totalElements} 条</span><label class="pagination-size-field">每页<select id="candidate-size" class="pagination-size">${pageSizeOptions(pageData.size)}</select></label></div>
    <div class="pagination-buttons">
      <button class="page-button" data-candidate-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>‹</button>
      <button class="page-button active" aria-current="page">${page} / ${totalPages}</button>
      <button class="page-button" data-candidate-page="${page + 1}" ${page >= totalPages ? "disabled" : ""}>›</button>
    </div>`;

  host.querySelectorAll("[data-candidate-page]").forEach((button) => {
    button.addEventListener("click", () => loadCandidates(Number(button.dataset.candidatePage)));
  });
  find("#candidate-size").addEventListener("change", (event) => {
    candidateState.size = Number(event.target.value);
    loadCandidates(1);
  });
}

async function loadCandidates(page = candidateState.page) {
  await loadSourceDictionary();
  candidateState.page = Math.max(1, page);
  const status = find("#candidate-status")?.value || "editing";
  const size = candidateState.size;
  const query = find("#candidate-query")?.value.trim() || "";
  const source = find("#candidate-source")?.value.trim() || "";
  const params = new URLSearchParams({ status, page: candidateState.page, size, q: query, source });
  const pageData = await api(`/api/admin/candidates?${params}`);
  const candidates = pageData.items || [];
  if (!candidates.length && pageData.totalPages > 0 && candidateState.page > pageData.totalPages) {
    return loadCandidates(pageData.totalPages);
  }

  find("#candidate-summary").textContent = `共 ${pageData.totalElements} 条 · 第 ${pageData.totalPages ? pageData.page : 0}/${pageData.totalPages} 页`;
  find("#candidates-table").innerHTML = renderTable(
    candidates,
    [
      ["选择", (row) => ["editing", "returned"].includes(row.status) ? `<input class="candidate-checkbox" type="checkbox" value="${row.id}" aria-label="选择候选词条 ${escapeHtml(row.term_raw)}">` : ""],
      ["ID", (row) => row.id],
      ["候选词形", (row) => `<strong>${escapeHtml(row.term_raw)}</strong>`],
      ["释义草稿", (row) => escapeHtml(row.definition_raw?.slice(0, 120)), "long-text"],
      ["来源", candidateSourceLabel],
      ["状态", (row) => statusBadge(row.status)],
      ["进入候选时间", (row) => escapeHtml(formatDateTime(row.created_at))],
    ],
    candidateActions,
  );
  renderCandidatePagination(pageData);
  bindCandidateActions();
  bindCandidateSelection();
}

function selectedCandidateIds() {
  return [...document.querySelectorAll(".candidate-checkbox:checked")].map((checkbox) => Number(checkbox.value));
}

function updateCandidateSelection() {
  const available = document.querySelectorAll(".candidate-checkbox").length;
  const count = selectedCandidateIds().length;
  find("#candidate-selected-count").textContent = `已选择 ${count} 条`;
  find("#candidate-batch-submit").disabled = count === 0;
  find("#candidate-select-all").disabled = available === 0;
  find("#candidate-invert-selection").disabled = available === 0;
}

function bindCandidateSelection() {
  document.querySelectorAll(".candidate-checkbox").forEach((checkbox) => {
    checkbox.addEventListener("change", updateCandidateSelection);
  });
  updateCandidateSelection();
}

function bindCandidateActions() {
  document.querySelectorAll("[data-candidate-detail]").forEach((button) => {
    button.addEventListener("click", () => showCandidateDetail(button.dataset.candidateDetail));
  });
  document.querySelectorAll("[data-candidate-edit]").forEach((button) => {
    button.addEventListener("click", () => openCandidateEditor(button.dataset.candidateEdit));
  });
  document.querySelectorAll("[data-candidate-submit]").forEach((button) => {
    button.addEventListener("click", async () => {
      button.disabled = true;
      try {
        await api(`/api/admin/candidates/${button.dataset.candidateSubmit}/submit`, { method: "POST" });
        showToast("候选词条已提交审核，审核期间不可编辑");
        await loadCandidates();
      } catch (error) {
        showToast(`提交失败：${error.message}`);
      } finally {
        button.disabled = false;
      }
    });
  });
  document.querySelectorAll("[data-published-entry]").forEach((button) => {
    button.addEventListener("click", () => showEntryDetail(button.dataset.publishedEntry));
  });
}

function candidateNote(row) {
  try {
    return JSON.parse(row.processing_note || "{}");
  } catch {
    return {};
  }
}

const INITIAL_EXAMPLE_DISPLAY_COUNT = 3;

function renderExampleCards(examples, renderExample, startIndex = 0) {
  return examples
    .map(
      (example, index) =>
        `<article class="example-card"><span class="example-number">例 ${startIndex + index + 1}</span><div>${renderExample(example)}</div></article>`,
    )
    .join("");
}

function renderExampleList(examples, renderExample) {
  if (!examples?.length) return "—";
  const visible = examples.slice(0, INITIAL_EXAMPLE_DISPLAY_COUNT);
  const remaining = examples.slice(INITIAL_EXAMPLE_DISPLAY_COUNT);
  const visibleCards = renderExampleCards(visible, renderExample);
  if (!remaining.length) return `<div class="example-list">${visibleCards}</div>`;
  return `<div class="example-list">${visibleCards}</div>
    <details class="example-overflow">
      <summary>展开其余 ${remaining.length} 条例句</summary>
      <div class="example-list">${renderExampleCards(remaining, renderExample, visible.length)}</div>
    </details>`;
}

function renderExampleSection(examples, renderExample) {
  return `<section class="detail-section"><div class="detail-section-heading"><h3>使用例句</h3><span>${examples?.length || 0} 条</span></div>${renderExampleList(examples, renderExample)}</section>`;
}

function candidateEditorCategory(note) {
  if (note.category) return note.category;
  const type = String(note.type_en || "").toLowerCase();
  if (type.includes("homoph")) return "homophone";
  if (type.includes("abbrev")) return "abbreviation";
  if (type.includes("template")) return "template_phrase";
  return "other";
}

let candidateEditorVariants = [];
let candidateEditorExamples = [];

function candidateAiVariantSources(variants = candidateEditorVariants) {
  const sources = [];
  const seen = new Set();
  variants
    .filter((item) => (item.sourceMethod || item.source_method) === "ai_suggested")
    .forEach((variant) => {
      (Array.isArray(variant.evidence) ? variant.evidence : []).forEach((item) => {
        const url = safeExternalUrl(item?.url);
        if (!url || seen.has(url)) return;
        seen.add(url);
        sources.push({ url, title: String(item.title || "联网搜索来源").trim() || "联网搜索来源" });
      });
    });
  return sources;
}

function renderCandidateAiVariantSources() {
  const container = find("#candidate-ai-variant-sources");
  const sources = candidateAiVariantSources();
  container.hidden = sources.length === 0;
  container.innerHTML = sources.length
    ? `<span>AI变体参考来源（${sources.length} 条）</span><div>${sources.map((item) => `<a href="${item.url}" target="_blank" rel="noreferrer">${escapeHtml(item.title)} ↗</a>`).join("")}</div>`
    : "";
}

function renderCandidateVariants() {
  const container = find("#candidate-variant-list");
  if (!candidateEditorVariants.length) {
    container.innerHTML = '<span class="section-empty">暂无词形变体</span>';
    renderCandidateAiVariantSources();
    return;
  }
  container.innerHTML = candidateEditorVariants.map((item, index) => `
    <div class="candidate-variant-chip"><div><strong>${escapeHtml(item.variant)}</strong><span>${escapeHtml(enumLabel(item.variantType))} · ${item.sourceMethod === "ai_suggested" ? "AI 生成" : "人工录入"}</span></div><button type="button" data-remove-candidate-variant="${index}" aria-label="删除 ${escapeHtml(item.variant)}">×</button></div>`).join("");
  container.querySelectorAll("[data-remove-candidate-variant]").forEach((button) => {
    button.addEventListener("click", () => {
      candidateEditorVariants.splice(Number(button.dataset.removeCandidateVariant), 1);
      renderCandidateVariants();
    });
  });
  renderCandidateAiVariantSources();
}

function renderCandidateExamples() {
  const container = find("#candidate-example-list");
  if (!candidateEditorExamples.length) {
    container.innerHTML = '<span class="section-empty">暂无使用例句</span>';
    return;
  }
  container.innerHTML = candidateEditorExamples.map((example, index) => `
    <div class="candidate-example-row"><span>${index + 1}</span><input type="text" maxlength="2000" value="${escapeHtml(example)}" data-candidate-example="${index}" aria-label="使用例句 ${index + 1}"><button type="button" data-remove-candidate-example="${index}" aria-label="删除例句 ${index + 1}">×</button></div>`).join("");
  container.querySelectorAll("[data-candidate-example]").forEach((input) => {
    input.addEventListener("input", () => {
      candidateEditorExamples[Number(input.dataset.candidateExample)] = input.value;
    });
  });
  container.querySelectorAll("[data-remove-candidate-example]").forEach((button) => {
    button.addEventListener("click", () => {
      candidateEditorExamples.splice(Number(button.dataset.removeCandidateExample), 1);
      renderCandidateExamples();
    });
  });
}

function addCandidateVariant(item) {
  const variant = String(item.variant || "").trim();
  const variantType = String(item.variantType || "alias").trim();
  if (!variant) return false;
  if (variant === find("#candidate-term").value.trim()) {
    showToast("词形变体不能与候选词形重复");
    return false;
  }
  if (candidateEditorVariants.some((value) => value.variant === variant && value.variantType === variantType)) {
    showToast("该词形变体已存在");
    return false;
  }
  candidateEditorVariants.push({ variant, variantType, confidence: item.confidence ?? 1, sourceMethod: item.sourceMethod === "ai_suggested" ? "ai_suggested" : "editorial", evidence: Array.isArray(item.evidence) ? item.evidence : [] });
  renderCandidateVariants();
  return true;
}

async function openCandidateEditor(candidateId = null) {
  try {
    find("#candidate-editor-form").reset();
    candidateEditorVariants = [];
    candidateEditorExamples = [];
    find("#candidate-editor-import-provenance").hidden = true;
    find("#candidate-editor-id").value = candidateId || "";
    find("#candidate-editor-title").textContent = candidateId ? "编辑候选词条" : "新增候选词条";
    if (candidateId) {
      const row = await api(`/api/admin/candidates/${candidateId}`);
      if (!["editing", "returned"].includes(row.status)) {
        showToast("无法打开编辑器：审核中的候选词条不允许编辑");
        return;
      }
      const note = candidateNote(row);
      find("#candidate-editor-import-provenance").hidden = false;
      find("#candidate-editor-import-source").textContent = row.source_type === "manual"
        ? "人工录入"
        : row.source_name || "未知来源";
      const provenance = [];
      if (row.file_name) provenance.push(`导入文件：${row.file_name}`);
      if (note.source_category) provenance.push(`来源分类：${note.source_category}`);
      if (Array.isArray(note.source_tags) && note.source_tags.length) provenance.push(`来源标签：${note.source_tags.join("、")}`);
      find("#candidate-editor-import-file").textContent = provenance.join(" · ");
      find("#candidate-term").value = row.term_raw || "";
      find("#candidate-definition").value = row.definition_raw || "";
      find("#candidate-category").value = candidateEditorCategory(note);
      find("#candidate-origin").value = note.origin || "";
      candidateEditorExamples = Array.isArray(note.examples)
        ? note.examples.map((example) => String(example)).filter((example) => example.trim())
        : [];
      find("#candidate-source-url").value = row.source_url || "";
      find("#candidate-profanity").checked = Boolean(note.profanity);
      find("#candidate-offense").checked = Boolean(note.offense);
      candidateEditorVariants = (Array.isArray(note.variants) ? note.variants : []).map((item) => ({
        variant: item.variant,
        variantType: item.variant_type || item.variantType || "alias",
        confidence: item.confidence ?? 1,
        sourceMethod: item.source_method || item.sourceMethod || "editorial",
        evidence: Array.isArray(item.evidence) ? item.evidence : [],
      }));
    }
    renderCandidateVariants();
    renderCandidateExamples();
    find("#candidate-editor-dialog").showModal();
    window.setTimeout(() => find("#candidate-term").focus(), 0);
  } catch (error) {
    showToast(`无法打开编辑器：${error.message}`);
  }
}

function candidateFormPayload() {
  return {
    term: find("#candidate-term").value.trim(),
    definition: find("#candidate-definition").value.trim(),
    category: find("#candidate-category").value,
    origin: find("#candidate-origin").value.trim(),
    examples: candidateEditorExamples.map((value) => value.trim()).filter(Boolean),
    sourceUrl: find("#candidate-source-url").value.trim(),
    profanity: find("#candidate-profanity").checked,
    offense: find("#candidate-offense").checked,
    variants: candidateEditorVariants,
  };
}

async function showCandidateDetail(candidateId) {
  try {
    const row = await api(`/api/admin/candidates/${candidateId}`);
    const note = candidateNote(row);
    const examples = Array.isArray(note.examples) ? note.examples : [];
    const sourceTags = Array.isArray(note.source_tags) ? note.source_tags : [];
    const variants = Array.isArray(note.variants) ? note.variants : [];
    const aiVariantSources = candidateAiVariantSources(variants);
    const aiExtraction = note.ai_extraction || note.ai_enrichment || null;
    const originReferences = Array.isArray(note.origin_references) ? note.origin_references.slice(0, 3) : [];
    find("#candidate-detail-content").innerHTML = `
      <dl class="detail-grid">
        <div class="detail-item"><dt>候选 ID</dt><dd>${row.id}</dd></div>
        <div class="detail-item"><dt>状态</dt><dd>${statusBadge(row.status)}</dd></div>
        <div class="detail-item"><dt>原始词形</dt><dd><strong>${escapeHtml(row.term_raw)}</strong></dd></div>
        <div class="detail-item"><dt>归一化词形</dt><dd>${escapeHtml(row.normalized_term)}</dd></div>
        <div class="detail-item wide"><dt>释义草稿</dt><dd class="detail-definition">${escapeHtml(row.definition_raw || "暂无释义")}</dd></div>
        ${aiExtraction?.needs_review ? `<div class="detail-item wide"><dt>复核提示</dt><dd><span class="badge warning">AI 提取结果待复核</span>${aiExtraction.issues?.length ? ` ${escapeHtml(aiExtraction.issues.join("、"))}` : ""}</dd></div>` : ""}
        <div class="detail-item"><dt>数据导入来源</dt><dd>${candidateSourceLabel(row)}</dd></div>
        <div class="detail-item"><dt>导入文件</dt><dd>${escapeHtml(row.file_name || "—")}</dd></div>
        <div class="detail-item"><dt>词条分类</dt><dd>${escapeHtml(enumLabel(note.category || candidateEditorCategory(note)))}</dd></div>
        ${note.source_category ? `<div class="detail-item"><dt>来源原始分类</dt><dd>${escapeHtml(note.source_category)}</dd></div>` : ""}
        ${sourceTags.length ? `<div class="detail-item wide"><dt>来源标签</dt><dd>${sourceTags.map((tag) => `<span class="variant-type-badge">${escapeHtml(tag)}</span>`).join(" ")}</dd></div>` : ""}
        <div class="detail-item"><dt>创建时间</dt><dd>${escapeHtml(row.created_at)}</dd></div>
        <div class="detail-item"><dt>更新时间</dt><dd>${escapeHtml(row.updated_at)}</dd></div>
        <div class="detail-item"><dt>提交人 / 时间</dt><dd>${escapeHtml(row.submitted_by || "—")} / ${escapeHtml(row.submitted_at || "—")}</dd></div>
        <div class="detail-item"><dt>审核人 / 时间</dt><dd>${escapeHtml(row.reviewed_by || "—")} / ${escapeHtml(row.reviewed_at || "—")}</dd></div>
        ${row.review_comment ? `<div class="detail-item wide"><dt>审核意见</dt><dd class="detail-definition">${escapeHtml(row.review_comment)}</dd></div>` : ""}
        <div class="detail-item wide"><dt>词条起源说明</dt><dd class="detail-definition">${escapeHtml(note.origin || "—")}</dd></div>
        <div class="detail-item wide"><dt>词条起源参考链接</dt><dd>${originReferences.length ? originReferences.map((item) => { const url = safeExternalUrl(item.url); return url ? `<p><a class="evidence-link" href="${url}" target="_blank" rel="noreferrer">${escapeHtml(item.title || item.url)} ↗</a></p>` : ""; }).join("") : "—"}</dd></div>
        <div class="detail-item wide"><dt>数据集来源地址</dt><dd>${safeExternalUrl(row.source_url) ? `<a class="evidence-link" href="${safeExternalUrl(row.source_url)}" target="_blank" rel="noreferrer">${escapeHtml(row.source_url)} ↗</a>` : "—"}</dd></div>
        <div class="detail-item"><dt>粗俗内容</dt><dd>${note.profanity ? "是" : "否"}</dd></div>
        <div class="detail-item"><dt>攻击性内容</dt><dd>${note.offense ? "是" : "否"}</dd></div>
        <div class="detail-item wide"><dt>使用例句 <span class="detail-count">${examples.length} 条</span></dt><dd>${renderExampleList(examples, (example) => `<p>${escapeHtml(example)}</p>`)}</dd></div>
      </dl>
      ${detailSection("词形变体", variants, (item) => {
        const type = item.variant_type || item.variantType || "alias";
        const source = item.source_method || item.sourceMethod || "editorial";
        return `<article class="variant-card"><strong>${escapeHtml(item.variant || "—")}</strong><div class="variant-card-meta"><span class="variant-type-badge">${escapeHtml(enumLabel(type))}</span><span>${escapeHtml(variantSourceLabel(source))}</span></div></article>`;
      })}
      ${detailSection("AI变体参考来源", aiVariantSources, (item) => `<article class="record-card"><strong>${escapeHtml(item.title)}</strong><a class="evidence-link" href="${item.url}" target="_blank" rel="noreferrer">打开来源 ↗</a></article>`)}`;
    find("#candidate-detail-dialog").showModal();
  } catch (error) {
    showToast(`详情加载失败：${error.message}`);
  }
}

const reviewState = { page: 1, size: 20, action: null, id: null, ids: [] };
const reviewActionConfig = {
  approve: { title: "批准并发布", description: "批准后将直接发布为正式词条，候选词条变为已发布状态。", endpoint: "approve", message: "候选词条已批准并发布", comment: true },
  return: { title: "退回修改", description: "退回后候选词条将恢复编辑权限，可修改后再次提交审核。", endpoint: "return", message: "候选词条已退回修改", comment: true, required: true, danger: true },
  batchApprove: { title: "批量批准并发布", description: "选中的候选词条将批量发布为正式词条。", endpoint: "batch-approve", message: "候选词条已批量批准并发布", comment: true, batch: true },
  batchReturn: { title: "批量退回修改", description: "选中的候选词条将恢复编辑权限，并使用同一条审核意见。", endpoint: "batch-return", message: "候选词条已批量退回修改", comment: true, required: true, danger: true, batch: true },
};

function reviewActions(candidate) {
  const detail = `<button class="table-action" data-review-detail="${candidate.id}">查看详情</button>`;
  if (candidate.status === "pending_review") {
    return `${detail}
      <button class="table-action" data-review-action="approve" data-review-id="${candidate.id}">批准发布</button>
      <button class="table-action danger-text" data-review-action="return" data-review-id="${candidate.id}">退回修改</button>`;
  }
  if (candidate.status === "published" && candidate.published_meme_id) {
    return `${detail}<button class="table-action" data-published-entry="${candidate.published_meme_id}">查看正式词条</button>`;
  }
  return detail;
}

function renderReviewPagination(pageData) {
  const host = find("#review-pagination");
  if (!pageData.totalElements) {
    host.innerHTML = "";
    return;
  }
  const page = Number(pageData.page);
  const totalPages = Number(pageData.totalPages);
  const start = (page - 1) * Number(pageData.size) + 1;
  const end = Math.min(page * Number(pageData.size), Number(pageData.totalElements));
  host.innerHTML = `
    <div class="pagination-info"><span>显示 ${start}–${end} 条，共 ${pageData.totalElements} 条</span><label class="pagination-size-field">每页<select id="review-size" class="pagination-size">${pageSizeOptions(pageData.size)}</select></label></div>
    <div class="pagination-buttons">
      <button class="page-button" data-review-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>‹</button>
      <button class="page-button active" aria-current="page">${page} / ${totalPages}</button>
      <button class="page-button" data-review-page="${page + 1}" ${page >= totalPages ? "disabled" : ""}>›</button>
    </div>`;
  host.querySelectorAll("[data-review-page]").forEach((button) => {
    button.addEventListener("click", () => loadReviews(Number(button.dataset.reviewPage)));
  });
  find("#review-size").addEventListener("change", (event) => {
    reviewState.size = Number(event.target.value);
    loadReviews(1);
  });
}

async function loadReviews(page = reviewState.page) {
  await loadSourceDictionary();
  reviewState.page = Math.max(1, page);
  const params = new URLSearchParams({
    status: find("#review-status").value,
    q: find("#review-query").value.trim(),
    source: find("#review-source").value.trim(),
    size: reviewState.size,
    page: reviewState.page,
  });
  const pageData = await api(`/api/admin/candidates?${params}`);
  const candidates = pageData.items || [];
  if (!candidates.length && pageData.totalPages > 0 && reviewState.page > pageData.totalPages) {
    return loadReviews(pageData.totalPages);
  }

  find("#review-summary").textContent = `共 ${pageData.totalElements} 条 · 第 ${pageData.totalPages ? pageData.page : 0}/${pageData.totalPages} 页`;
  find("#reviews-table").innerHTML = renderTable(
    candidates,
    [
      ["选择", (row) => row.status === "pending_review" ? `<input class="review-checkbox" type="checkbox" value="${row.id}" aria-label="选择审核候选 ${escapeHtml(row.term_raw)}">` : ""],
      ["ID", (row) => row.id],
      ["候选词形", (row) => `<strong>${escapeHtml(row.term_raw)}</strong>`],
      ["释义", (row) => escapeHtml(row.definition_raw?.slice(0, 120)), "long-text"],
      ["审核状态", (row) => statusBadge(row.status)],
      ["来源", candidateSourceLabel],
      ["提交人", (row) => escapeHtml(row.submitted_by || "—")],
      ["提交时间", (row) => escapeHtml(row.submitted_at || "—")],
    ],
    reviewActions,
  );
  renderReviewPagination(pageData);
  bindReviewActions();
  bindReviewSelection();
}

function selectedReviewIds() {
  return [...document.querySelectorAll(".review-checkbox:checked")].map((checkbox) => Number(checkbox.value));
}

function updateReviewSelection() {
  const available = document.querySelectorAll(".review-checkbox").length;
  const count = selectedReviewIds().length;
  find("#review-selected-count").textContent = `已选择 ${count} 条`;
  find("#review-batch-approve").disabled = count === 0;
  find("#review-batch-return").disabled = count === 0;
  find("#review-select-all").disabled = available === 0;
  find("#review-invert-selection").disabled = available === 0;
}

function bindReviewSelection() {
  document.querySelectorAll(".review-checkbox").forEach((checkbox) => {
    checkbox.addEventListener("change", updateReviewSelection);
  });
  updateReviewSelection();
}

function bindReviewActions() {
  document.querySelectorAll("[data-review-detail]").forEach((button) => {
    button.addEventListener("click", () => showReviewDetail(button.dataset.reviewDetail));
  });
  document.querySelectorAll("[data-review-action]").forEach((button) => {
    button.addEventListener("click", () => openReviewAction(button.dataset.reviewAction, button.dataset.reviewId));
  });
  document.querySelectorAll("[data-published-entry]").forEach((button) => {
    button.addEventListener("click", () => showEntryDetail(button.dataset.publishedEntry));
  });
}

function openReviewAction(action, id = null, ids = []) {
  const config = reviewActionConfig[action];
  reviewState.action = action;
  reviewState.id = id;
  reviewState.ids = ids;
  find("#review-action-title").textContent = config.title;
  find("#review-action-description").textContent = config.batch ? `${config.description} 共 ${ids.length} 条。` : config.description;
  find("#review-action-comment").value = "";
  find("#review-comment-field").classList.toggle("is-hidden", !config.comment);
  const confirm = find("#confirm-review-action");
  confirm.textContent = config.title;
  confirm.className = config.danger ? "danger-button" : "primary-button";
  find("#review-action-dialog").showModal();
}

async function showReviewDetail(id) {
  try {
    const row = await api(`/api/admin/candidates/${id}`);
    const note = candidateNote(row);
    const examples = Array.isArray(note.examples) ? note.examples : [];
    find("#review-detail-content").innerHTML = `
      <div class="change-banner"><div><span class="panel-kicker">CANDIDATE #${row.id}</span><h3>${escapeHtml(row.term_raw)}</h3><p>${escapeHtml(row.definition_raw)}</p></div><div class="change-banner-meta">${statusBadge(row.source_type)}${statusBadge(row.status)}</div></div>
      <div class="review-audit-grid">
        <div class="policy-item"><span>录入来源</span><strong>${candidateSourceLabel(row)}</strong></div>
        <div class="policy-item"><span>提交人 / 时间</span><strong>${escapeHtml(row.submitted_by || "—")}</strong><span>${escapeHtml(row.submitted_at || "—")}</span></div>
        <div class="policy-item"><span>审核人 / 时间</span><strong>${escapeHtml(row.reviewed_by || "—")}</strong><span>${escapeHtml(row.reviewed_at || "—")}</span></div>
      </div>
      ${row.review_comment ? `<section class="detail-section"><div class="detail-section-heading"><h3>审核意见</h3></div><p class="snapshot-note">${escapeHtml(row.review_comment)}</p></section>` : ""}
      <section class="detail-section"><div class="detail-section-heading"><h3>候选内容</h3><span>${escapeHtml(enumLabel(note.category || candidateEditorCategory(note)))}</span></div><dl class="detail-grid">
        <div class="detail-item"><dt>候选词形</dt><dd><strong>${escapeHtml(row.term_raw)}</strong></dd></div>
        <div class="detail-item"><dt>归一化词形</dt><dd>${escapeHtml(row.normalized_term)}</dd></div>
        <div class="detail-item wide"><dt>完整释义</dt><dd class="detail-definition">${escapeHtml(row.definition_raw)}</dd></div>
        <div class="detail-item wide"><dt>来源或起源说明</dt><dd>${escapeHtml(note.origin || "—")}</dd></div>
        <div class="detail-item"><dt>粗俗内容</dt><dd>${note.profanity ? "是" : "否"}</dd></div>
        <div class="detail-item"><dt>攻击性内容</dt><dd>${note.offense ? "是" : "否"}</dd></div>
        <div class="detail-item wide"><dt>来源链接</dt><dd>${escapeHtml(row.source_url || row.import_source_url || "—")}</dd></div>
      </dl></section>
      ${renderExampleSection(examples, (example) => `<p>${escapeHtml(example)}</p>`)}
    `;
    find("#review-detail-dialog").showModal();
  } catch (error) {
    showToast(`详情加载失败：${error.message}`);
  }
}

const entryState = { page: 1, size: 20, selectedIds: new Set() };

function selectedEntryIds() {
  return [...document.querySelectorAll(".entry-checkbox:checked")].map((checkbox) => Number(checkbox.value));
}

function updateEntrySelection() {
  const ids = selectedEntryIds();
  entryState.selectedIds = new Set(ids);
  find("#entry-selection-count").textContent = `已选择 ${ids.length} 条`;
  find("#entry-batch-withdraw").disabled = ids.length === 0;
  find("#entry-select-all").disabled = document.querySelectorAll(".entry-checkbox").length === 0;
  find("#entry-invert-selection").disabled = document.querySelectorAll(".entry-checkbox").length === 0;
}

async function withdrawEntries(ids) {
  const count = ids.length;
  try {
    const path = count === 1 ? `/api/admin/entries/${ids[0]}/withdraw` : "/api/admin/entries/batch-withdraw";
    const options = count === 1
      ? { method: "POST" }
      : { method: "POST", body: JSON.stringify({ ids }) };
    const result = await api(path, options);
    showToast(count === 1 ? `已撤回至候选池（候选 #${result.candidateId}）` : `已撤回 ${result.withdrawnCount} 条词条至候选池`);
    entryState.selectedIds.clear();
    await loadEntries(entryState.page);
    await loadCandidates(1);
  } catch (error) {
    showToast(`撤回失败：${error.message}`);
  }
}

function renderEntryPagination(pageData) {
  const host = find("#entry-pagination");
  if (!pageData.totalElements) {
    host.innerHTML = "";
    return;
  }
  const page = Number(pageData.page);
  const totalPages = Number(pageData.totalPages);
  const start = (page - 1) * Number(pageData.size) + 1;
  const end = Math.min(page * Number(pageData.size), Number(pageData.totalElements));
  host.innerHTML = `
    <div class="pagination-info"><span>显示 ${start}–${end} 条，共 ${pageData.totalElements} 条</span><label class="pagination-size-field">每页<select id="entry-size" class="pagination-size">${pageSizeOptions(pageData.size)}</select></label></div>
    <div class="pagination-buttons">
      <button class="page-button" data-entry-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>‹</button>
      <button class="page-button active" aria-current="page">${page} / ${totalPages}</button>
      <button class="page-button" data-entry-page="${page + 1}" ${page >= totalPages ? "disabled" : ""}>›</button>
    </div>`;
  host.querySelectorAll("[data-entry-page]").forEach((button) => {
    button.addEventListener("click", () => loadEntries(Number(button.dataset.entryPage)));
  });
  find("#entry-size").addEventListener("change", (event) => {
    entryState.size = Number(event.target.value);
    loadEntries(1);
  });
}

async function loadEntries(page = entryState.page) {
  await loadSourceDictionary();
  entryState.page = Math.max(1, page);
  const params = new URLSearchParams({
    q: find("#entry-query").value.trim(),
    source: find("#entry-source").value.trim(),
    status: find("#entry-status").value,
    riskLevel: find("#entry-risk").value,
    size: entryState.size,
    page: entryState.page,
  });
  const pageData = await api(`/api/admin/entries?${params}`);
  const entries = pageData.items || [];
  if (!entries.length && pageData.totalPages > 0 && entryState.page > pageData.totalPages) {
    return loadEntries(pageData.totalPages);
  }

  find("#entry-summary").textContent = `共 ${pageData.totalElements} 条 · 第 ${pageData.totalPages ? pageData.page : 0}/${pageData.totalPages} 页`;
  find("#entries-table").innerHTML = renderTable(
    entries,
    [
      ["选择", (row) => row.status === "published" ? `<input class="entry-checkbox" type="checkbox" value="${row.id}" aria-label="选择正式词条 ${escapeHtml(row.canonical_term)}">` : ""],
      ["ID", (row) => row.id],
      ["词条编号", (row) => escapeHtml(row.meme_code)],
      ["标准词形", (row) => `<strong>${escapeHtml(row.canonical_term)}</strong>`],
      ["词条释义", (row) => escapeHtml(row.primary_definition || "暂无释义"), "long-text"],
      ["分类", (row) => escapeHtml(enumLabel(row.category))],
      ["风险等级", (row) => statusBadge(row.risk_level)],
      ["当前版本", (row) => `V${row.current_version}`],
      ["发布状态", (row) => statusBadge(row.status)],
    ],
    (row) => `<button class="table-action" data-entry-detail="${row.id}">查看详情</button>${row.status === "published" ? `<button class="table-action danger-action" data-entry-withdraw="${row.id}">撤回</button>` : ""}`,
  );
  renderEntryPagination(pageData);
  document.querySelectorAll("[data-entry-detail]").forEach((button) => {
    button.addEventListener("click", () => showEntryDetail(button.dataset.entryDetail));
  });
  document.querySelectorAll("[data-entry-withdraw]").forEach((button) => {
    button.addEventListener("click", () => withdrawEntries([Number(button.dataset.entryWithdraw)]));
  });
  document.querySelectorAll(".entry-checkbox").forEach((checkbox) => {
    checkbox.addEventListener("change", updateEntrySelection);
  });
  updateEntrySelection();
}

function detailSection(title, items, renderItem, collapsedAfter = null) {
  const rows = Array.isArray(items) ? items : [];
  const shouldCollapse = Number.isInteger(collapsedAfter) && rows.length > collapsedAfter;
  const visible = shouldCollapse ? rows.slice(0, collapsedAfter) : rows;
  const hidden = shouldCollapse ? rows.slice(collapsedAfter) : [];
  const content = rows.length
    ? `<div class="record-list">${visible.map(renderItem).join("")}</div>${hidden.length ? `<details class="record-overflow"><summary><span class="record-overflow-closed">展开其余 ${hidden.length} 条</span><span class="record-overflow-open">收起其余 ${hidden.length} 条</span></summary><div class="record-list">${hidden.map(renderItem).join("")}</div></details>` : ""}`
    : '<div class="section-empty">暂无数据</div>';
  return `<section class="detail-section"><div class="detail-section-heading"><h3>${title}</h3><span>${rows.length} 条</span></div>${content}</section>`;
}

function renderSenseCard(item) {
  const shortDefinition = String(item.short_definition || "").trim();
  const definition = String(item.definition || "").trim();
  const sameDefinition = shortDefinition === definition || !shortDefinition || !definition;
  const heading = sameDefinition ? `义项 ${item.sense_no}` : `${item.sense_no}. ${escapeHtml(shortDefinition)}`;
  return `<article class="record-card"><strong>${heading}</strong><p>${escapeHtml(definition || shortDefinition || "暂无释义")}</p><div class="record-meta"><span>语气：${escapeHtml(enumLabel(item.polarity))}</span><span>正式度：${escapeHtml(enumLabel(item.formality))}</span><span>状态：${escapeHtml(enumLabel(item.status))}</span></div></article>`;
}

function variantSourceLabel(source) {
  return {
    ai_suggested: "AI 生成",
    editorial: "人工维护",
    rule_generated: "规则生成",
    source_observed: "来源采集",
  }[source] || "未知来源";
}

function renderVariantCard(item) {
  const confidence = item.confidence == null ? null : `${Math.round(Number(item.confidence) * 100)}%`;
  return `<article class="variant-card"><strong>${escapeHtml(item.variant)}</strong><div class="variant-card-meta"><span class="variant-type-badge">${escapeHtml(enumLabel(item.variant_type))}</span><span>${escapeHtml(variantSourceLabel(item.source_method))}</span>${confidence ? `<span>置信度 ${confidence}</span>` : ""}</div></article>`;
}

async function showEntryDetail(entryId) {
  try {
    const detail = await api(`/api/admin/entries/${entryId}`);
    const snapshot = detail.snapshot || {};
    const entry = snapshot.meme_entry || {};
    const policy = snapshot.safety_policy || {};
    const variants = snapshot.variants || [];
    const senses = snapshot.senses || [];
    const examples = snapshot.examples || [];
    const rules = snapshot.match_rules || [];
    const evidence = snapshot.evidence || [];
    const variantEvidence = evidence.filter((item) => item.evidence_role === "variant");
    const discoveryEvidence = evidence.filter((item) => item.evidence_role === "discovery");
    const entryEvidence = evidence.filter((item) => !["variant", "discovery"].includes(item.evidence_role));
    const revisions = detail.revisions || [];

    find("#entry-detail-content").innerHTML = `
      <div class="entry-summary-card">
        <div><span class="panel-kicker">${escapeHtml(entry.meme_code)}</span><h3>${escapeHtml(entry.canonical_term)}</h3><p><strong>词条起源：</strong>${escapeHtml(entry.origin_summary || "暂无说明")}</p></div>
        <div class="entry-summary-meta">${statusBadge(entry.status)}${statusBadge(policy.risk_level)}${entry.status === "published" ? `<button type="button" class="danger-button" data-entry-withdraw="${escapeHtml(entry.id)}">撤回至候选池</button>` : ""}</div>
      </div>
      <dl class="detail-grid">
        <div class="detail-item"><dt>归一化词形</dt><dd>${escapeHtml(entry.normalized_term)}</dd></div>
        <div class="detail-item"><dt>分类 / 语言</dt><dd>${escapeHtml(enumLabel(entry.category))} / ${escapeHtml(enumLabel(entry.language_code))}</dd></div>
        <div class="detail-item"><dt>当前版本</dt><dd>V${entry.current_version ?? 0}</dd></div>
        <div class="detail-item"><dt>创建人 / 审核人</dt><dd>${escapeHtml(entry.created_by || "—")} / ${escapeHtml(entry.reviewed_by || "—")}</dd></div>
        <div class="detail-item"><dt>发布时间</dt><dd>${escapeHtml(entry.published_at || "—")}</dd></div>
      </dl>
      ${detailSection("义项", senses, renderSenseCard)}
      <section class="detail-section"><div class="detail-section-heading"><h3>词形变体</h3><div class="variant-heading-actions"><span>${variants.length} 条</span>${detail.variantGenerationEnabled && entry.status === "published" ? '<button type="button" class="ai-variant-button" data-regenerate-variants="' + escapeHtml(entry.id) + '"><span aria-hidden="true">✦</span>AI生成变体</button><span class="ai-variant-progress" role="status" hidden>正在生成 AI 变体，请稍候…</span>' : ""}</div></div>${variants.length ? `<div class="variant-list">${variants.map(renderVariantCard).join("")}</div>` : '<div class="section-empty">暂无数据</div>'}</section>
      ${renderExampleSection(examples, (item) => `<p>${escapeHtml(item.example_text)}</p><div class="record-meta"><span>${escapeHtml(enumLabel(item.example_role))}</span><span>${escapeHtml(enumLabel(item.status))}</span></div>`)}
      ${detailSection("匹配规则", rules, (item) => `<article class="record-card"><strong>${escapeHtml(enumLabel(item.rule_type))}</strong><p>${escapeHtml(item.rule_value)}</p><div class="record-meta"><span>权重：${item.weight}</span><span>优先级：${item.priority}</span><span>${item.enabled ? "已启用" : "已停用"}</span></div></article>`)}
      <section class="detail-section"><div class="detail-section-heading"><h3>安全策略</h3></div><div class="policy-grid">
        <div class="policy-item"><span>风险等级</span><strong>${escapeHtml(enumLabel(policy.risk_level))}</strong></div>
        <div class="policy-item"><span>识别</span><strong>${policy.detect_enabled ? "允许" : "禁止"}</strong></div>
        <div class="policy-item"><span>展示</span><strong>${policy.display_enabled ? "允许" : "禁止"}</strong></div>
        <div class="policy-item"><span>生成</span><strong>${policy.generate_enabled ? "允许" : "禁止"}</strong></div>
        <div class="policy-item"><span>推荐</span><strong>${policy.recommend_enabled ? "允许" : "禁止"}</strong></div>
        <div class="policy-item"><span>审核策略</span><strong>${escapeHtml(enumLabel(policy.moderation_policy))}</strong></div>
      </div></section>
      ${detailSection("数据导入来源", discoveryEvidence, (item) => `<article class="record-card"><strong>${escapeHtml(item.source_name)}</strong><p>${escapeHtml(item.evidence_note || "—")}</p></article>`, 3)}
      ${entryEvidence.length ? detailSection("其他词条证据", entryEvidence, (item) => `<article class="record-card"><strong>${escapeHtml(item.source_name)}</strong><p>${escapeHtml(item.evidence_note || item.source_url || "—")}</p>${safeExternalUrl(item.source_url) ? `<a class="evidence-link" href="${safeExternalUrl(item.source_url)}" target="_blank" rel="noreferrer">打开来源 ↗</a>` : ""}<div class="record-meta"><span>${escapeHtml(enumLabel(item.source_layer))}</span><span>${escapeHtml(enumLabel(item.evidence_role))}</span><span>可信度：${item.confidence ?? "—"}</span></div></article>`, 3) : ""}
      ${detailSection("AI变体参考来源", variantEvidence, (item) => `<article class="record-card"><strong>${escapeHtml(item.source_name)}</strong><p>${escapeHtml(item.evidence_note || item.source_url || "—")}</p>${safeExternalUrl(item.source_url) ? `<a class="evidence-link" href="${safeExternalUrl(item.source_url)}" target="_blank" rel="noreferrer">打开来源 ↗</a>` : ""}<div class="record-meta"><span>${escapeHtml(enumLabel(item.source_layer))}</span><span>可信度：${item.confidence ?? "—"}</span></div></article>`, 3)}
      ${detailSection("版本记录", revisions, (item) => `<article class="record-card"><strong>V${item.version} · ${escapeHtml(enumLabel(item.change_type))}</strong><p>${escapeHtml(item.change_summary || "暂无变更说明")}</p><div class="record-meta"><span>变更人：${escapeHtml(item.changed_by || "—")}</span><span>审核人：${escapeHtml(item.reviewed_by || "—")}</span><span>${escapeHtml(item.created_at)}</span></div></article>`, 3)}
    `;
    find("#entry-detail-content")
      .querySelectorAll("[data-regenerate-variants]")
      .forEach((button) => {
        button.addEventListener("click", async () => {
          const progress = button.parentElement.querySelector(".ai-variant-progress");
          button.disabled = true;
          progress.hidden = false;
          try {
            await api(`/api/admin/entries/${button.dataset.regenerateVariants}/regenerate-variants`, {
              method: "POST",
            });
            showToast("AI 变体已重新生成");
            await showEntryDetail(button.dataset.regenerateVariants);
            await loadEntries(entryState.page);
          } catch (error) {
            showToast(error.message);
          } finally {
            button.disabled = false;
            progress.hidden = true;
          }
        });
      });
    find("#entry-detail-content")
      .querySelectorAll("[data-entry-withdraw]")
      .forEach((button) => button.addEventListener("click", async () => {
        await withdrawEntries([Number(button.dataset.entryWithdraw)]);
        find("#entry-detail-dialog").close();
      }));
    const dialog = find("#entry-detail-dialog");
    if (!dialog.open) dialog.showModal();
  } catch (error) {
    showToast(`详情加载失败：${error.message}`);
  }
}

const overviewState = { tab: "governance", metrics: {} };

function overviewTime(value) {
  if (!value) return "—";
  return String(value).replace("T", " ").slice(0, 16);
}

function renderOverviewStats() {
  const selected = overviewState.metrics[overviewState.tab] || [];
  const icons = {
    governance: ["候", "编", "审", "文"],
    imports: ["批", "入", "误", "时"],
    crawler: ["爬", "入", "误", "时"],
  };
  find("#stats").innerHTML = selected
    .map(([label, value], index) => {
      const compact = typeof value === "string" && value.length > 8 ? "compact-value" : "";
      return `
        <div class="stat-card">
          <span>${escapeHtml(label)}</span>
          <strong class="${compact}">${escapeHtml(value)}</strong>
          <small aria-hidden="true">${icons[overviewState.tab]?.[index] || "·"}</small>
        </div>`;
    })
    .join("");
  document.querySelectorAll("[data-overview-tab]").forEach((button) => {
    const active = button.dataset.overviewTab === overviewState.tab;
    button.classList.toggle("active", active);
    button.setAttribute("aria-selected", String(active));
  });
}

async function loadOverview() {
  const hour = new Date().getHours();
  find("#overview-greeting").textContent = hour >= 5 && hour < 12 ? "早上好" : hour < 18 ? "下午好" : "晚上好";
  const [importSummary, crawlSources, candidates, editing, returned, reviews, entries] = await Promise.all([
    api("/api/admin/imports/summary"),
    api("/api/admin/v3/crawl-sources"),
    api("/api/admin/candidates?status=all&page=1&size=10"),
    api("/api/admin/candidates?status=editing&page=1&size=10"),
    api("/api/admin/candidates?status=returned&page=1&size=10"),
    api("/api/admin/candidates?status=pending_review&page=1&size=10"),
    api("/api/admin/entries?status=published&riskLevel=all&page=1&size=10"),
  ]);

  const crawled = crawlSources.reduce(
    (total, source) => total + (source.record_summary || [])
      .filter((item) => ["imported", "duplicate", "ignored", "failed"].includes(item.status))
      .reduce((subtotal, item) => subtotal + (Number(item.count) || 0), 0),
    0,
  );
  const crawlerImported = crawlSources.reduce(
    (total, source) => total + (source.record_summary || [])
      .filter((item) => item.status === "imported")
      .reduce((subtotal, item) => subtotal + (Number(item.count) || 0), 0),
    0,
  );
  const crawlerFailed = crawlSources.reduce(
    (total, source) => total + (source.record_summary || [])
      .filter((item) => item.status === "failed")
      .reduce((subtotal, item) => subtotal + (Number(item.count) || 0), 0),
    0,
  );
  const lastCrawlAt = crawlSources
    .map((source) => source.last_successful_at)
    .filter(Boolean)
    .sort()
    .at(-1);
  overviewState.metrics = {
    governance: [
      ["候选词条", candidates.totalElements || 0],
      ["待编辑候选", (Number(editing.totalElements) || 0) + (Number(returned.totalElements) || 0)],
      ["待审核候选", reviews.totalElements || 0],
      ["正式词条", entries.totalElements || 0],
    ],
    imports: [
      ["导入运行", importSummary.run_count || 0],
      ["累计导入候选", importSummary.candidate_count || 0],
      ["导入失败", importSummary.failed_count || 0],
      ["最近导入", overviewTime(importSummary.last_import_at)],
    ],
    crawler: [
      ["累计爬取", crawled],
      ["累计进入候选", crawlerImported],
      ["爬取失败", crawlerFailed],
      ["最近爬取", overviewTime(lastCrawlAt)],
    ],
  };
  renderOverviewStats();
}

document.querySelectorAll("[data-overview-tab]").forEach((button) => {
  button.addEventListener("click", () => {
    overviewState.tab = button.dataset.overviewTab;
    renderOverviewStats();
  });
});

async function loadIndexTasks() {
  const status = find("#index-task-status").value;
  const data = await api(`/api/admin/recognition-v2/index/tasks?status=${encodeURIComponent(status)}&page=1&size=50`);
  const counts = Object.fromEntries((data.summary || []).map((item) => [item.status, item.count]));
  find("#index-task-summary").innerHTML = ["pending", "processing", "failed", "succeeded"].map((key) => `<div class="stat-card"><span>${enumLabel(key)}</span><strong>${counts[key] || 0}</strong></div>`).join("");
  find("#index-task-count").textContent = `共 ${data.totalElements || 0} 条`;
  find("#index-task-table").innerHTML = renderTable(data.items || [], [
    ["词条", (row) => `#${row.meme_id}`], ["操作", (row) => enumLabel(row.operation)], ["状态", (row) => statusBadge(row.status)], ["重试", (row) => row.retry_count], ["失败原因", (row) => escapeHtml(row.last_error || "—")], ["更新时间", (row) => row.updated_at || "—"], ["操作", (row) => row.status === "failed" ? `<button class="table-action" data-index-task-retry="${row.id}">重新入队</button>` : "—"],
  ]);
  document.querySelectorAll("[data-index-task-retry]").forEach((button) => button.addEventListener("click", async () => { await api(`/api/admin/recognition-v2/index/tasks/${button.dataset.indexTaskRetry}/retry`, { method: "POST" }); showToast("任务已重新入队"); await loadIndexTasks(); }));
}

const crawlState = { page: 1, size: 20, activeSource: null };
let crawlPollTimer = null;
let currentCrawlError = "";

function crawlRecordBadge(status) {
  const labels = {
    pending: "待处理", processing: "待处理", retry_wait: "待处理",
    imported: "已导入", duplicate: "已存在", ignored: "已忽略", failed: "失败",
  };
  const tone = status === "imported" ? "success" : ["pending", "processing", "retry_wait"].includes(status) ? "info" : status === "failed" ? "warning" : "";
  return `<span class="badge ${tone}">${escapeHtml(labels[status] || status)}</span>`;
}

function crawlRecordResult(row) {
  const error = row.error_message?.trim();
  const summary = error
    ? `<button type="button" class="crawl-error-summary" data-crawl-error="${row.id}" title="点击查看并复制完整错误">${escapeHtml(error)}</button>`
    : "";
  return `<div class="crawl-result">${crawlRecordBadge(row.status)}${summary}</div>`;
}

function openCrawlError(error) {
  currentCrawlError = error;
  find("#crawl-error-content").textContent = error;
  find("#crawl-error-dialog").showModal();
}

function updateCrawlerSourceOptions(sources) {
  const active = find("#crawl-active-source");
  const record = find("#crawl-record-source");
  const sourceOptions = sources.map((source) => `<option value="${escapeHtml(source.source_code)}">${escapeHtml(source.source_name)}</option>`).join("");
  if (!crawlState.activeSource || (crawlState.activeSource !== "all" && !sources.some((source) => source.source_code === crawlState.activeSource))) {
    crawlState.activeSource = "all";
  }
  active.innerHTML = `<option value="all">全部来源</option>${sourceOptions}`;
  active.value = crawlState.activeSource;
  const recordValue = record.value || "all";
  record.innerHTML = `<option value="all">全部来源</option>${sourceOptions}`;
  record.value = [...record.options].some((option) => option.value === recordValue) ? recordValue : "all";
}

function aggregateCrawlerSources(sources) {
  const statusCounts = {};
  for (const source of sources) {
    for (const item of source.record_summary || []) {
      statusCounts[item.status] = (statusCounts[item.status] || 0) + (Number(item.count) || 0);
    }
  }
  const statuses = sources.map((source) => source.current_status);
  const currentStatus = statuses.includes("planning")
    ? "planning"
    : statuses.includes("running")
      ? "running"
      : statuses.some((status) => ["partial", "failed"].includes(status))
        ? "partial"
        : "idle";
  const sum = (field) => sources.reduce((total, source) => total + (Number(source[field]) || 0), 0);
  return {
    source_code: "all",
    source_name: "全部来源",
    current_status: currentStatus,
    discovered_count: sum("discovered_count"),
    imported_count: sum("imported_count"),
    duplicate_count: sum("duplicate_count"),
    ignored_count: sum("ignored_count"),
    failed_count: sum("failed_count"),
    record_summary: Object.entries(statusCounts).map(([status, count]) => ({ status, count })),
  };
}

function updateCrawlerProgress(source) {
  const progress = find("#crawl-progress");
  const active = ["planning", "running"].includes(source.current_status);
  if (active) {
    const processed = (source.imported_count || 0) + (source.duplicate_count || 0) + (source.ignored_count || 0) + (source.failed_count || 0);
    progress.textContent = source.source_code === "all"
      ? "有来源正在同步，请选择具体来源查看进度或停止任务。"
      : source.current_status === "planning"
      ? `正在准备 ${source.source_name} 的同步任务…`
      : `正在同步 ${source.source_name}：已处理 ${processed} / ${source.discovered_count || 0} 条。`;
    progress.dataset.state = "running";
    progress.hidden = false;
  } else if (source.current_status === "partial") {
    progress.textContent = `${source.source_name} 有 ${source.failed_count || 0} 条同步失败，再次点击“立即同步”会自动重试。`;
    progress.dataset.state = "partial";
    progress.hidden = false;
  } else {
    progress.hidden = true;
    progress.textContent = "";
    progress.dataset.state = "idle";
  }
  if (active && !crawlPollTimer) {
    crawlPollTimer = window.setInterval(() => loadCrawler(crawlState.page).catch(() => {}), 3000);
  } else if (!active && crawlPollTimer) {
    window.clearInterval(crawlPollTimer);
    crawlPollTimer = null;
  }
}

function renderCrawlerPagination(pageData) {
  const host = find("#crawl-record-pagination");
  if (!pageData.totalElements) {
    host.innerHTML = "";
    return;
  }
  const pageValue = Number(pageData.page);
  const totalPagesValue = Number(pageData.totalPages);
  const sizeValue = Number(pageData.size);
  const page = Number.isFinite(pageValue) ? Math.max(1, Math.trunc(pageValue)) : 1;
  const totalPages = Number.isFinite(totalPagesValue) ? Math.max(1, Math.trunc(totalPagesValue)) : 1;
  const size = Number.isFinite(sizeValue) ? Math.max(1, Math.trunc(sizeValue)) : crawlState.size;
  const start = (page - 1) * size + 1;
  const end = Math.min(page * size, Number(pageData.totalElements));
  host.innerHTML = `
    <div class="pagination-info"><span>显示 ${start}–${end} 条，共 ${pageData.totalElements} 条</span><label class="pagination-size-field">每页<select id="crawl-record-size" class="pagination-size">${pageSizeOptions(pageData.size)}</select></label></div>
    <div class="pagination-buttons">
      <button class="page-button" data-crawl-record-page="${page - 1}" ${page <= 1 ? "disabled" : ""}>‹</button>
      <button class="page-button active" aria-current="page">${page} / ${totalPages}</button>
      <button class="page-button" data-crawl-record-page="${page + 1}" ${page >= totalPages ? "disabled" : ""}>›</button>
    </div>`;
  host.querySelectorAll("[data-crawl-record-page]").forEach((button) => {
    button.addEventListener("click", () => loadCrawler(Number(button.dataset.crawlRecordPage)));
  });
  find("#crawl-record-size").addEventListener("change", (event) => {
    crawlState.size = Number(event.target.value);
    loadCrawler(1);
  });
}

async function loadCrawler(page = crawlState.page) {
  const requestedPage = Number(page);
  crawlState.page = Number.isFinite(requestedPage) ? Math.max(1, Math.trunc(requestedPage)) : 1;
  const sources = await api("/api/admin/v3/crawl-sources");
  updateCrawlerSourceOptions(sources);
  if (!crawlState.activeSource) {
    find("#crawl-source-summary").innerHTML = '<div class="empty-state">暂无可用来源</div>';
    return;
  }
  const source = crawlState.activeSource === "all"
    ? aggregateCrawlerSources(sources)
    : sources.find((item) => item.source_code === crawlState.activeSource);
  if (!source) return;
  updateCrawlerProgress(source);
  const checkpoint = source.checkpoint
    ? (typeof source.checkpoint === "string" ? JSON.parse(source.checkpoint) : source.checkpoint)
    : null;
  const cumulative = Object.fromEntries(
    (source.record_summary || []).map((item) => [item.status, Number(item.count) || 0]),
  );
  const cumulativeProcessed = ["imported", "duplicate", "ignored", "failed"]
    .reduce((total, status) => total + (cumulative[status] || 0), 0);
  const statistics = [
    ["累计处理", cumulativeProcessed],
    ["累计进入候选", cumulative.imported || 0],
    ["本次处理", source.discovered_count || 0],
    ["本次进入候选", source.imported_count || 0],
  ];
  find("#crawl-source-summary").innerHTML = statistics
    .map(([label, value]) => `<div class="stat-card"><span>${escapeHtml(label)}</span><strong>${escapeHtml(value)}</strong></div>`)
    .join("");
  const allSources = crawlState.activeSource === "all";
  find("#crawl-sync").disabled = allSources || !source.enabled || ["planning", "running"].includes(source.current_status);
  find("#crawl-cancel").hidden = allSources || !["planning", "running"].includes(source.current_status);
  find("#crawl-source-summary").title = allSources ? "全部来源汇总" : checkpoint ? `检查点：${JSON.stringify(checkpoint)}` : "尚未建立检查点";

  const status = find("#crawl-record-status").value;
  const recordSource = find("#crawl-record-source").value;
  const data = await api(`/api/admin/v3/crawl-sources/records?source=${encodeURIComponent(recordSource)}&status=${encodeURIComponent(status)}&page=${crawlState.page}&size=${crawlState.size}`);
  if (!data.items?.length && data.totalPages > 0 && crawlState.page > data.totalPages) {
    return loadCrawler(data.totalPages);
  }
  find("#crawl-record-count").textContent = `共 ${data.totalElements || 0} 条`;
  const crawlRecords = (data.items || []).map((row, index) => ({
    ...row,
    display_index: (Number(data.page) - 1) * Number(data.size) + index + 1,
  }));
  const crawlErrors = new Map(
    crawlRecords.filter((row) => row.error_message).map((row) => [String(row.id), row.error_message]),
  );
  find("#crawl-records-table").innerHTML = renderTable(crawlRecords, [
    ["序号", (row) => row.display_index],
    ["来源", (row) => escapeHtml(row.source_name)],
    ["词条", (row) => escapeHtml(row.source_term || row.normalized_term || row.source_record_key)],
    ["结果", crawlRecordResult],
    ["归一化词形", (row) => escapeHtml(row.normalized_term || "—")],
    ["候选", (row) => row.candidate_id ? `#${row.candidate_id}` : "—"],
    ["爬取时间", (row) => escapeHtml(formatDateTime(row.fetched_at))],
    ["原网页", (row) => { const url = safeExternalUrl(row.source_url); return url ? `<a href="${url}" target="_blank" rel="noopener noreferrer">查看</a>` : "—"; }],
  ]);
  document.querySelectorAll("[data-crawl-error]").forEach((button) => {
    button.addEventListener("click", () => openCrawlError(crawlErrors.get(button.dataset.crawlError) || "未知错误"));
  });
  renderCrawlerPagination(data);
}

async function crawlAction(action, confirmation) {
  if (!crawlState.activeSource || crawlState.activeSource === "all") return;
  if (confirmation && !window.confirm(confirmation)) return;
  try {
    const result = await api(`/api/admin/v3/crawl-sources/${encodeURIComponent(crawlState.activeSource)}/${action}`, { method: "POST" });
    if (action === "sync" && result.sync_outcome === "no_change") {
      showToast("未发现新内容，检查点保持不变");
    } else if (action === "sync" && result.sync_outcome === "checkpoint_updated") {
      showToast("没有待处理词条，检查点已更新");
    } else if (action === "sync") {
      showToast(`同步已启动，${result.queued_count || 0} 条词条等待处理`);
    } else {
      showToast("同步已停止");
    }
    await loadCrawler();
  } catch (error) {
    showToast(error.message);
  }
}

async function loadPage(pageName) {
  try {
    if (pageName !== "crawler" && crawlPollTimer) {
      window.clearInterval(crawlPollTimer);
      crawlPollTimer = null;
    }
    const loaders = {
      overview: loadOverview,
      imports: loadImports,
      crawler: loadCrawler,
      candidates: loadCandidates,
      reviews: loadReviews,
      entries: loadEntries,
      "index-tasks": loadIndexTasks,
    };
    if (loaders[pageName]) {
      await loaders[pageName]();
    }
  } catch (error) {
    showToast(error.message);
  }
}

find("#refresh-files").addEventListener("click", loadFiles);
find("#crawl-refresh").addEventListener("click", () => loadCrawler());
find("#crawl-record-search").addEventListener("click", () => loadCrawler(1));
find("#crawl-active-source").addEventListener("change", (event) => {
  crawlState.activeSource = event.target.value;
  find("#crawl-record-source").value = event.target.value;
  loadCrawler(1);
});
find("#crawl-sync").addEventListener("click", () => crawlAction("sync"));
find("#crawl-cancel").addEventListener("click", () => crawlAction("cancel", "确认停止当前同步吗？"));
find("#copy-crawl-error").addEventListener("click", async () => {
  try {
    await navigator.clipboard.writeText(currentCrawlError);
    showToast("错误信息已复制");
  } catch {
    const range = document.createRange();
    range.selectNodeContents(find("#crawl-error-content"));
    const selection = window.getSelection();
    selection.removeAllRanges();
    selection.addRange(range);
    showToast("无法自动复制，已选中文本，请按 Ctrl+C");
  }
});
find("#index-task-refresh").addEventListener("click", loadIndexTasks);
find("#index-task-search").addEventListener("click", loadIndexTasks);
find("#import-source").addEventListener("change", () => {
  find("#source-version").value = "manual-local";
  find("#license-status").value = "approved";
  find("#rights-note").value = "V1 manual CHIME import approved";
  loadFiles();
});
find("[data-refresh='candidates']").addEventListener("click", () => loadCandidates(candidateState.page));
find("#candidate-search").addEventListener("click", () => loadCandidates(1));
find("#create-candidate").addEventListener("click", () => openCandidateEditor());
find("#candidate-select-all").addEventListener("click", () => {
  document.querySelectorAll(".candidate-checkbox").forEach((checkbox) => {
    checkbox.checked = true;
  });
  updateCandidateSelection();
});
find("#candidate-invert-selection").addEventListener("click", () => {
  document.querySelectorAll(".candidate-checkbox").forEach((checkbox) => {
    checkbox.checked = !checkbox.checked;
  });
  updateCandidateSelection();
});
find("#candidate-batch-submit").addEventListener("click", async () => {
  const ids = selectedCandidateIds();
  if (!ids.length) {
    showToast("请先选择需要提交审核的候选词条");
    return;
  }
  if (!window.confirm(`确认将选中的 ${ids.length} 条候选词条提交审核吗？提交后将暂时锁定编辑。`)) {
    return;
  }
  const button = find("#candidate-batch-submit");
  button.disabled = true;
  try {
    const result = await api("/api/admin/candidates/batch-submit", {
      method: "POST",
      body: JSON.stringify({ ids }),
    });
    showToast(`已提交 ${result.submittedCount} 条候选词条进入审核`);
    await loadCandidates(candidateState.page);
  } catch (error) {
    showToast(`批量提交失败：${error.message}`);
    updateCandidateSelection();
  }
});
find("#candidate-editor-form").addEventListener("submit", async (event) => {
  event.preventDefault();
  const id = find("#candidate-editor-id").value;
  const button = find("#save-candidate");
  button.disabled = true;
  try {
    await api(id ? `/api/admin/candidates/${id}` : "/api/admin/candidates", {
      method: id ? "PUT" : "POST",
      body: JSON.stringify(candidateFormPayload()),
    });
    find("#candidate-editor-dialog").close();
    showToast(id ? "候选词条已更新" : "候选词条已创建");
    if (!id) {
      find("#candidate-status").value = "editing";
    }
    await loadCandidates(1);
  } catch (error) {
    showToast(`保存失败：${error.message}`);
  } finally {
    button.disabled = false;
  }
});
find("#add-candidate-variant").addEventListener("click", () => {
  if (addCandidateVariant({
    variant: find("#candidate-variant-value").value,
    variantType: find("#candidate-variant-type").value,
    sourceMethod: "editorial",
  })) {
    find("#candidate-variant-value").value = "";
    find("#candidate-variant-value").focus();
  }
});
find("#add-candidate-example").addEventListener("click", () => {
  candidateEditorExamples.push("");
  renderCandidateExamples();
  find(`#candidate-example-list [data-candidate-example="${candidateEditorExamples.length - 1}"]`)?.focus();
});
find("#candidate-variant-value").addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    event.preventDefault();
    find("#add-candidate-variant").click();
  }
});
find("#generate-candidate-variants").addEventListener("click", async () => {
  const term = find("#candidate-term").value.trim();
  const definition = find("#candidate-definition").value.trim();
  if (!term || !definition) {
    showToast("请先填写候选词形和释义，再生成 AI 变体");
    return;
  }
  const button = find("#generate-candidate-variants");
  const progress = find("#candidate-variant-progress");
  button.disabled = true;
  progress.hidden = false;
  try {
    const result = await api("/api/admin/candidates/generate-variants", {
      method: "POST",
      body: JSON.stringify({
        term,
        definition,
        retainedVariants: candidateEditorVariants.filter((item) => item.sourceMethod !== "ai_suggested"),
      }),
    });
    candidateEditorVariants = candidateEditorVariants.filter((item) => item.sourceMethod !== "ai_suggested");
    (result.variants || []).forEach(addCandidateVariant);
    renderCandidateVariants();
    showToast("AI 词形变体已生成，可删除不需要的项后保存");
  } catch (error) {
    showToast(`AI 变体生成失败：${error.message}`);
  } finally {
    button.disabled = false;
    progress.hidden = true;
  }
});
find("#candidate-status").addEventListener("change", () => loadCandidates(1));
find("#candidate-query").addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    loadCandidates(1);
  }
});
find("#candidate-source").addEventListener("change", () => loadCandidates(1));
document.querySelectorAll("[data-close-dialog]").forEach((button) => {
  button.addEventListener("click", () => find(`#${button.dataset.closeDialog}`).close());
});
find("#confirm-review-action").addEventListener("click", async () => {
  const config = reviewActionConfig[reviewState.action];
  const comment = find("#review-action-comment").value.trim();
  if (config.required && !comment) {
    showToast("退回修改时必须填写审核意见");
    find("#review-action-comment").focus();
    return;
  }

  const button = find("#confirm-review-action");
  button.disabled = true;
  const approvalAction = ["approve", "batchApprove"].includes(reviewState.action);
  const originalLabel = button.textContent;
  if (approvalAction) {
    button.textContent = "正在处理…";
    find("#review-action-description").textContent =
      "正在批准词条；如已开启 AI 变体生成，系统会一并生成变体。请稍候，不要重复提交。";
  }
  try {
    const options = { method: "POST" };
    const path = config.batch
      ? `/api/admin/candidates/${config.endpoint}`
      : `/api/admin/candidates/${reviewState.id}/${config.endpoint}`;
    if (config.batch) {
      options.body = JSON.stringify({ ids: reviewState.ids, comment });
    } else if (config.comment) {
      options.body = JSON.stringify({ comment });
    }
    await api(path, options);
    find("#review-action-dialog").close();
    showToast(config.batch ? `${config.message}，共 ${reviewState.ids.length} 条` : config.message);
    await loadReviews(reviewState.page);
  } catch (error) {
    showToast(`操作失败：${error.message}`);
  } finally {
    button.disabled = false;
    button.textContent = originalLabel;
  }
});
find("#review-search").addEventListener("click", () => loadReviews(1));
find("#review-select-all").addEventListener("click", () => {
  document.querySelectorAll(".review-checkbox").forEach((checkbox) => {
    checkbox.checked = true;
  });
  updateReviewSelection();
});
find("#review-invert-selection").addEventListener("click", () => {
  document.querySelectorAll(".review-checkbox").forEach((checkbox) => {
    checkbox.checked = !checkbox.checked;
  });
  updateReviewSelection();
});
find("#review-batch-approve").addEventListener("click", () => {
  const ids = selectedReviewIds();
  if (!ids.length) return showToast("请先选择需要批准的候选词条");
  openReviewAction("batchApprove", null, ids);
});
find("#review-batch-return").addEventListener("click", () => {
  const ids = selectedReviewIds();
  if (!ids.length) return showToast("请先选择需要退回的候选词条");
  openReviewAction("batchReturn", null, ids);
});
find("#review-status").addEventListener("change", () => loadReviews(1));
find("#review-query").addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    loadReviews(1);
  }
});
find("#review-source").addEventListener("change", () => loadReviews(1));
find("#entry-search").addEventListener("click", () => loadEntries(1));
find("#entry-select-all").addEventListener("click", () => {
  document.querySelectorAll(".entry-checkbox").forEach((checkbox) => { checkbox.checked = true; });
  updateEntrySelection();
});
find("#entry-invert-selection").addEventListener("click", () => {
  document.querySelectorAll(".entry-checkbox").forEach((checkbox) => { checkbox.checked = !checkbox.checked; });
  updateEntrySelection();
});
find("#entry-batch-withdraw").addEventListener("click", () => withdrawEntries(selectedEntryIds()));
find("#entry-status").addEventListener("change", () => loadEntries(1));
find("#entry-risk").addEventListener("change", () => loadEntries(1));
find("#entry-query").addEventListener("keydown", (event) => {
  if (event.key === "Enter") {
    loadEntries(1);
  }
});
find("#entry-source").addEventListener("change", () => loadEntries(1));

find("#run-import").addEventListener("click", async () => {
  const button = find("#run-import");
  const progress = find("#import-progress");
  if (button.disabled) return;
  try {
    const source = find("#import-source").value;
    const fileName = find("#import-file").value;
    button.disabled = true;
    button.setAttribute("aria-busy", "true");
    progress.textContent = `正在导入 ${source.toUpperCase()}：${fileName}。请勿重复提交或关闭页面。`;
    progress.hidden = false;
    const run = await api(`/api/admin/imports/${encodeURIComponent(source)}`, {
      method: "POST",
      body: JSON.stringify({
        fileName,
        sourceVersion: find("#source-version").value,
        licenseStatus: find("#license-status").value,
        upstreamRightsNote: find("#rights-note").value,
      }),
    });
    showToast(run.reused ? "该文件版本已导入，已返回原任务" : "导入任务已创建，后台正在处理");
    await loadImports();
  } catch (error) {
    showToast(error.message);
  } finally {
    button.disabled = false;
    button.removeAttribute("aria-busy");
    progress.hidden = true;
    progress.textContent = "";
  }
});

find("#import-record-search").addEventListener("click", () => {
  importState.status = find("#import-record-status").value;
  importState.query = find("#import-record-query").value.trim();
  loadImportRecords(1).catch((error) => showToast(`词条列表加载失败：${error.message}`));
});
find("#import-record-status").addEventListener("change", () => {
  importState.status = find("#import-record-status").value;
  loadImportRecords(1).catch((error) => showToast(`词条列表加载失败：${error.message}`));
});
find("#import-record-query").addEventListener("keydown", (event) => {
  if (event.key === "Enter") find("#import-record-search").click();
});
find("#import-run-detail-close").addEventListener("click", () => {
  importState.runId = null;
  find("#import-run-detail").hidden = true;
});
find("#import-retry-failed").addEventListener("click", async () => {
  if (!importState.runId) return;
  try {
    const result = await api(`/api/admin/imports/${importState.runId}/retry`, { method: "POST" });
    showToast(result.retriedCount ? `已重新入队 ${result.retriedCount} 条失败词条` : "当前没有可重试的失败词条");
    await loadImportRecords(1);
    await loadImports();
  } catch (error) { showToast(`重新处理失败：${error.message}`); }
});

find("#recognize").addEventListener("click", async () => {
  const minConfidence = Number(find("#recognition-min-confidence").value);
  const maxResults = Number(find("#recognition-max-results").value);
  if (!Number.isFinite(minConfidence) || minConfidence < 0 || minConfidence > 1) {
    showToast("最低置信度必须在 0 到 1 之间");
    return;
  }
  if (!Number.isInteger(maxResults) || maxResults < 1 || maxResults > 200) {
    showToast("最大结果数必须在 1 到 200 之间");
    return;
  }

  const button = find("#recognize");
  const status = find("#recognition-status");
  const statusText = find("#recognition-status-text");
  const durationText = find("#recognition-response-duration");
  const output = find("#recognition-result");
  const startedAt = performance.now();
  const elapsedText = () => {
    const milliseconds = performance.now() - startedAt;
    return milliseconds < 1000 ? `${Math.round(milliseconds)} ms` : `${(milliseconds / 1000).toFixed(2)} s`;
  };

  button.disabled = true;
  button.setAttribute("aria-busy", "true");
  button.textContent = "请求中…";
  status.dataset.state = "requesting";
  statusText.textContent = "请求已发送，正在等待响应…";
  durationText.textContent = "响应耗时：计时中…";
  output.textContent = "正在请求识别服务，请稍候…";

  try {
    const result = await api("/api/v2/recognitions", {
      method: "POST",
      body: JSON.stringify({
        text: find("#recognition-text").value,
        language_code: find("#recognition-language-code").value.trim() || "zh-CN",
        options: {
          min_confidence: minConfidence,
          max_results: maxResults,
          enable_semantic_recall: find("#recognition-enable-semantic").checked,
        },
      }),
    });
    const duration = elapsedText();
    output.textContent = JSON.stringify(result, null, 2);
    status.dataset.state = "success";
    statusText.textContent = "响应成功，结果已更新";
    durationText.textContent = `响应耗时：${duration}`;
    showToast(`识别请求响应成功，耗时 ${duration}`);
  } catch (error) {
    const duration = elapsedText();
    status.dataset.state = "error";
    statusText.textContent = "请求失败";
    durationText.textContent = `响应耗时：${duration}`;
    output.textContent = `请求失败：${error.message}`;
    showToast(error.message);
  } finally {
    button.disabled = false;
    button.removeAttribute("aria-busy");
    button.textContent = "开始测试";
  }
});

loadOverview();
