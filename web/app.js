import { initializeApp } from "https://www.gstatic.com/firebasejs/12.19.0/firebase-app.js";
import {
  createUserWithEmailAndPassword,
  getAuth,
  onAuthStateChanged,
  sendPasswordResetEmail,
  signInWithEmailAndPassword,
  signOut,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-auth.js";
import {
  collection,
  deleteDoc,
  doc,
  getFirestore,
  initializeFirestore,
  onSnapshot,
  persistentLocalCache,
  persistentMultipleTabManager,
  setDoc,
} from "https://www.gstatic.com/firebasejs/12.19.0/firebase-firestore.js";
import { firebaseConfig, isFirebaseConfigured } from "./firebase-config.js";

const $ = (selector, root = document) => root.querySelector(selector);
const app = initializeApp(firebaseConfig);
const auth = getAuth(app);
let db;
try {
  db = initializeFirestore(app, {
    localCache: persistentLocalCache({ tabManager: persistentMultipleTabManager() }),
  });
} catch {
  db = getFirestore(app);
}

const COLORS = ["#FF4F6BED", "#FF7B61A8", "#FF168A72", "#FFD47D35", "#FFD24D57", "#FF3883B8", "#FF758B3D"];
const HEADERS = ["任务标识", "任务名称", "任务备注", "任务分类", "任务类型", "开始日期", "结束日期", "周期规则生效日期", "执行日期", "每周重复日", "间隔天数", "每月执行日", "提醒开关", "提醒时间", "提前提醒分钟数", "暂停状态"];
const model = {
  user: null,
  tab: "today",
  categoryFilter: "all",
  selectedDate: localDateString(),
  selectedMonth: localDateString().slice(0, 7),
  historyDate: "",
  tasks: new Map(),
  categories: new Map(),
  occurrences: new Map(),
  deletions: new Map(),
  unsubscribers: [],
  authMode: "login",
  syncMessage: "正在连接…",
  notificationKeys: new Set(),
};

if ("serviceWorker" in navigator) {
  navigator.serviceWorker.register("./sw.js", { scope: "./" }).catch(() => {});
}

function localDateString(date = new Date()) {
  const local = new Date(date.getTime() - date.getTimezoneOffset() * 60000);
  return local.toISOString().slice(0, 10);
}
function newId() { return crypto.randomUUID?.() || `${Date.now()}-${Math.random().toString(16).slice(2)}`; }
function escapeHtml(value = "") {
  return String(value).replace(/[&<>"']/g, character => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[character]);
}
function normalizeName(value) { return value.trim().toLowerCase(); }
function categoryDocId(name) {
  const bytes = new TextEncoder().encode(normalizeName(name));
  let binary = "";
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}
function occurrenceKey(taskId, date) { return `${taskId}__${date}`; }
function deletionDocId(collectionName, documentId) {
  const bytes = new TextEncoder().encode(`${collectionName}:${documentId}`);
  let binary = "";
  bytes.forEach(byte => { binary += String.fromCharCode(byte); });
  return btoa(binary).replaceAll("+", "-").replaceAll("/", "_").replace(/=+$/, "");
}
function taskCategory(task) { return task.categoryName || "未分类"; }
function cssColor(value = "#FF98A2B3") { return value.length === 9 ? `#${value.slice(3)}` : value; }
function colorFor(task) { return cssColor(model.categories.get(categoryDocId(taskCategory(task)))?.colorHex || "#FF98A2B3"); }
function formatDate(date, options = { month: "long", day: "numeric" }) {
  return new Intl.DateTimeFormat("zh-CN", options).format(new Date(`${date}T12:00:00`));
}
function weekdayLabel(date) { return new Intl.DateTimeFormat("zh-CN", { weekday: "long" }).format(new Date(`${date}T12:00:00`)); }
function showToast(message) {
  const toast = $("#toast");
  toast.textContent = message;
  toast.classList.add("show");
  clearTimeout(showToast.timer);
  showToast.timer = setTimeout(() => toast.classList.remove("show"), 2800);
}
function setAuthMessage(message, error = true) {
  const element = $("#auth-message");
  element.textContent = message;
  element.classList.toggle("danger-text", error);
}
function friendlyError(error) {
  const messages = {
    "auth/email-already-in-use": "这个邮箱已经注册过了。",
    "auth/invalid-email": "邮箱格式不正确。",
    "auth/weak-password": "密码至少需要 6 位。",
    "auth/invalid-credential": "邮箱或密码不正确。",
    "auth/user-not-found": "没有找到这个账号。",
    "auth/wrong-password": "密码不正确。",
    "auth/too-many-requests": "尝试次数过多，请稍后再试。",
    "auth/network-request-failed": "网络连接失败，请检查网络后重试。",
  };
  return messages[error?.code] || error?.message || "操作失败，请稍后重试。";
}

onAuthStateChanged(auth, user => {
  model.user = user;
  model.unsubscribers.forEach(unsubscribe => unsubscribe());
  model.unsubscribers = [];
  model.tasks.clear();
  model.categories.clear();
  model.occurrences.clear();
  model.deletions.clear();
  $("#auth-screen").hidden = !!user;
  $("#plan-screen").hidden = !user;
  $("#config-message").hidden = isFirebaseConfigured;
  $("#account-chip").hidden = !user;
  if (user) {
    $("#account-chip").innerHTML = `<span>${escapeHtml(user.email || "已登录")}</span><button class="text-button" data-action="sign-out" type="button">退出</button>`;
    subscribeToCloud(user.uid);
  } else {
    $("#account-chip").textContent = "";
  }
  render();
});

function subscribeToCloud(uid) {
  model.syncMessage = "正在同步…";
  const userCollection = name => collection(db, "users", uid, name);
  const subscribe = (name, target, keyFn) => model.unsubscribers.push(onSnapshot(
    userCollection(name),
    snapshot => {
      const next = new Map(snapshot.docs.map(item => [keyFn(item.id, item.data()), item.data()]));
      target.clear();
      next.forEach((value, key) => target.set(key, value));
      applyTombstones();
      model.syncMessage = snapshot.metadata.fromCache ? "显示本机缓存，等待网络同步" : "云端已同步";
      render();
      if (name === "tasks" || name === "occurrences") checkOpenPageReminders();
    },
    error => {
      model.syncMessage = `同步暂停：${friendlyError(error)}`;
      render();
    },
  ));
  subscribe("tasks", model.tasks, id => id);
  subscribe("categories", model.categories, id => id);
  subscribe("occurrences", model.occurrences, id => id);
  subscribe("deletions", model.deletions, id => id);
}

function applyTombstones() {
  for (const deletion of model.deletions.values()) {
    const target = deletion.collectionName === "categories" ? model.categories : deletion.collectionName === "occurrences" ? model.occurrences : null;
    if (!target) continue;
    const record = target?.get(deletion.documentId);
    if (!record || Number(deletion.deletedAt) >= Number(record.updatedAt || 0)) target.delete(deletion.documentId);
  }
}

function nextUpdatedAt(collectionName, documentId, after = 0) {
  const deletedAt = Number(model.deletions.get(deletionDocId(collectionName, documentId))?.deletedAt || 0);
  return Math.max(Date.now(), deletedAt + 1, Number(after || 0) + 1);
}

function writeDeletion(collectionName, documentId, after = 0) {
  const deletedAt = Math.max(Date.now(), Number(after || 0) + 1);
  return setDoc(doc(db, "users", model.user.uid, "deletions", deletionDocId(collectionName, documentId)), {
    collectionName,
    documentId,
    deletedAt,
  });
}

$("#auth-form").addEventListener("submit", async event => {
  event.preventDefault();
  if (!isFirebaseConfigured) return setAuthMessage("请先完成 Firebase 配置，再注册或登录。");
  const email = $("#auth-email").value.trim();
  const password = $("#auth-password").value;
  const submit = $("#auth-submit");
  submit.disabled = true;
  setAuthMessage(model.authMode === "signup" ? "正在创建账号…" : "正在登录…", false);
  try {
    if (model.authMode === "signup") {
      const credential = await createUserWithEmailAndPassword(auth, email, password);
      const defaults = [["科研", "#FF4F6BED"], ["学习", "#FF7B61A8"], ["工作", "#FF168A72"], ["生活", "#FFD47D35"], ["运动", "#FFD24D57"]];
      await Promise.all(defaults.map(([name, colorHex], sortOrder) => setDoc(
        doc(db, "users", credential.user.uid, "categories", categoryDocId(name)),
        { name, normalizedName: normalizeName(name), colorHex, sortOrder, createdAt: Date.now(), updatedAt: Date.now() },
      )));
    } else await signInWithEmailAndPassword(auth, email, password);
  } catch (error) {
    setAuthMessage(friendlyError(error));
  } finally {
    submit.disabled = false;
  }
});
$("#auth-toggle").addEventListener("click", () => {
  model.authMode = model.authMode === "login" ? "signup" : "login";
  $("#auth-submit").textContent = model.authMode === "signup" ? "创建账号" : "登录";
  $("#auth-password").autocomplete = model.authMode === "signup" ? "new-password" : "current-password";
  $("#auth-toggle").textContent = model.authMode === "signup" ? "返回登录" : "创建新账号";
  setAuthMessage("");
});
$("#auth-reset").addEventListener("click", async () => {
  const email = $("#auth-email").value.trim();
  if (!email) return setAuthMessage("请先填写邮箱地址。");
  try {
    await sendPasswordResetEmail(auth, email);
    setAuthMessage("重置密码邮件已发送，请查看邮箱。", false);
  } catch (error) { setAuthMessage(friendlyError(error)); }
});

function currentOccurrence(task, date) { return model.occurrences.get(occurrenceKey(task.externalId, date)); }
function occursOn(task, dateText) {
  if (!task || task.isDeleted || task.isPaused) return false;
  if (task.type === "once") return task.startDate === dateText;
  if (dateText < task.startDate || (task.ruleEffectiveFrom && dateText < task.ruleEffectiveFrom) || (task.endDate && dateText > task.endDate)) return false;
  if (task.type === "daily" || task.repeatKind === "daily") return true;
  const date = new Date(`${dateText}T12:00:00`);
  if (task.repeatKind === "weekly") {
    const mondayIndex = date.getDay() === 0 ? 7 : date.getDay();
    return String(task.weeklyDays || "1,3,5").split(",").map(Number).includes(mondayIndex);
  }
  if (task.repeatKind === "interval") {
    const start = new Date(`${task.startDate}T12:00:00`);
    const elapsed = Math.round((date - start) / 86400000);
    return elapsed >= 0 && elapsed % Math.max(1, Number(task.intervalDays) || 1) === 0;
  }
  if (task.repeatKind === "monthly") {
    const requestedDay = Math.max(1, Math.min(31, Number(task.monthlyDay) || 1));
    const lastDay = new Date(date.getFullYear(), date.getMonth() + 1, 0).getDate();
    return date.getDate() === Math.min(requestedDay, lastDay);
  }
  return false;
}
function tasksForDay(date) {
  return [...model.tasks.values()].filter(task => {
    if (task.isDeleted || task.isPaused) return false;
    if (task.type === "once" && task.startDate < date) {
      const oldOccurrence = currentOccurrence(task, task.startDate);
      return !oldOccurrence?.isCompleted;
    }
    return occursOn(task, date);
  }).sort((a, b) => (a.startDate || "").localeCompare(b.startDate || "") || (a.createdAt || 0) - (b.createdAt || 0));
}
function dueDateFor(task, date) { return task.type === "once" && task.startDate < date ? task.startDate : date; }
function categoryChips() {
  const categories = [...model.categories.values()].filter(item => !item.isDeleted).sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
  return `<div class="filter-row"><button class="filter-chip ${model.categoryFilter === "all" ? "active" : ""}" data-action="filter" data-id="all" type="button">全部</button><button class="filter-chip ${model.categoryFilter === "unclassified" ? "active" : ""}" data-action="filter" data-id="unclassified" type="button">未分类</button>${categories.map(category => `<button class="filter-chip ${model.categoryFilter === categoryDocId(category.name) ? "active" : ""}" data-action="filter" data-id="${escapeHtml(categoryDocId(category.name))}" type="button"><span class="dot" style="background:${escapeHtml(cssColor(category.colorHex || "#FF98A2B3"))}"></span> ${escapeHtml(category.name)}</button>`).join("")}</div>`;
}
function taskPassesCategory(task) {
  if (model.categoryFilter === "all") return true;
  if (model.categoryFilter === "unclassified") return !task.categoryName || task.categoryName === "未分类";
  return categoryDocId(task.categoryName || "") === model.categoryFilter;
}
function taskCard(task, date, showCompletion = true) {
  const dueDate = dueDateFor(task, date);
  const occurrence = currentOccurrence(task, dueDate);
  const isComplete = !!occurrence?.isCompleted;
  const repeatText = task.type === "once" ? `单次 · ${task.startDate}` : task.type === "daily" ? "每日" : task.repeatKind === "weekly" ? `每周 · ${task.weeklyDays.split(",").map(day => ["","一","二","三","四","五","六","日"][Number(day)]).join("、")}` : task.repeatKind === "interval" ? `每隔 ${task.intervalDays} 天` : `每月 ${task.monthlyDay} 日`;
  const paused = !!task.isPaused;
  const desc = task.description ? `<div class="task-meta">${escapeHtml(task.description)}</div>` : "";
  return `<article class="task-card ${isComplete ? "is-complete" : ""}" data-task="${escapeHtml(task.externalId)}">
    ${showCompletion ? `<button class="check-button ${isComplete ? "checked" : ""}" data-action="toggle-complete" data-id="${escapeHtml(task.externalId)}" data-date="${dueDate}" aria-label="${isComplete ? "撤销完成" : "标记完成"}" type="button">${isComplete ? "✓" : ""}</button>` : `<span class="check-button ${paused ? "" : "checked"}" aria-hidden="true">${paused ? "Ⅱ" : "•"}</span>`}
    <div class="task-main"><div class="task-title">${escapeHtml(task.title)}</div>${desc}<div class="task-meta"><span class="category-dot"><i class="dot" style="background:${escapeHtml(colorFor(task))}"></i>${escapeHtml(taskCategory(task))}</span><span>${escapeHtml(repeatText)}</span>${dueDate !== date ? `<span>逾期 ${escapeHtml(dueDate)}</span>` : ""}${task.reminderEnabled ? `<span>提醒 ${escapeHtml(task.reminderTime)}</span>` : ""}${paused ? `<span>已暂停</span>` : ""}</div></div>
    <div class="task-actions"><button class="icon-button" data-action="edit-task" data-id="${escapeHtml(task.externalId)}" aria-label="编辑" type="button">✎</button><button class="icon-button" data-action="pause-task" data-id="${escapeHtml(task.externalId)}" aria-label="${paused ? "恢复" : "暂停"}" type="button">${paused ? "▶" : "Ⅱ"}</button><button class="icon-button" data-action="delete-task" data-id="${escapeHtml(task.externalId)}" aria-label="删除" type="button">⋯</button></div>
  </article>`;
}
function renderToday() {
  const today = localDateString();
  const due = tasksForDay(today).filter(taskPassesCategory);
  const completed = due.filter(task => currentOccurrence(task, dueDateFor(task, today))?.isCompleted).length;
  const percent = due.length ? Math.round(completed / due.length * 100) : 0;
  return `<div class="summary-grid"><article class="card summary-card"><span class="muted">今日进度</span><span class="value">${completed} / ${due.length}</span><div class="progress-track"><div class="progress-fill" style="width:${percent}%"></div></div></article><article class="card summary-card"><span class="muted">待完成</span><span class="value">${due.length - completed}</span></article><article class="card summary-card"><span class="muted">当前计划</span><span class="value">${[...model.tasks.values()].filter(task => !task.isDeleted && !task.isPaused).length}</span></article></div>
    ${categoryChips()}<div class="section-heading"><h2>今天要做</h2><span class="sync-state">${escapeHtml(model.syncMessage)}</span></div>
    <div class="task-list">${due.length ? due.map(task => taskCard(task, today)).join("") : `<div class="card empty-state"><div style="font-size:32px">☀</div><strong>${model.tasks.size ? "今天没有符合条件的任务" : "先添加一个任务吧"}</strong><span>完成一小步，今天就会更有进展。</span></div>`}</div>`;
}
function renderPlans() {
  const date = model.selectedDate;
  const all = [...model.tasks.values()].filter(task => !task.isDeleted && taskPassesCategory(task));
  const daily = all.filter(task => occursOn(task, date) || (task.type === "once" && task.startDate <= date && !currentOccurrence(task, task.startDate)?.isCompleted));
  const paused = all.filter(task => task.isPaused);
  return `<div class="card date-bar"><label>查看日期<input id="plan-date" type="date" value="${escapeHtml(date)}" /></label><span class="sync-state">${escapeHtml(model.syncMessage)}</span></div>${categoryChips()}<div class="section-heading"><h2>${escapeHtml(formatDate(date, { year: "numeric", month: "long", day: "numeric", weekday: "short" }))} · ${daily.length} 项</h2></div><div class="task-list">${daily.length ? daily.map(task => taskCard(task, date)).join("") : `<div class="card empty-state"><strong>这一天没有计划任务</strong><span>可以换一天查看，或新建一个任务。</span></div>`}</div>${paused.length ? `<div class="section-heading"><h2>已暂停 · ${paused.length} 项</h2></div><div class="task-list">${paused.map(task => taskCard(task, date, false)).join("")}</div>` : ""}`;
}
function monthStats(month) {
  const items = [...model.occurrences.values()].filter(item => item.isCompleted && item.date?.startsWith(month));
  const days = new Map();
  items.forEach(item => days.set(item.date, (days.get(item.date) || 0) + 1));
  return { items: items.sort((a, b) => b.date.localeCompare(a.date) || (b.completedAt || 0) - (a.completedAt || 0)), days };
}
function renderHistory() {
  const stats = monthStats(model.selectedMonth);
  const [year, monthNumber] = model.selectedMonth.split("-").map(Number);
  const first = new Date(year, monthNumber - 1, 1);
  const offset = (first.getDay() + 6) % 7;
  const count = new Date(year, monthNumber, 0).getDate();
  const calendar = Array.from({ length: 42 }, (_, index) => {
    const day = index - offset + 1;
    if (day < 1 || day > count) return `<span class="calendar-day outside"></span>`;
    const key = `${model.selectedMonth}-${String(day).padStart(2, "0")}`;
    const countOnDay = stats.days.get(key) || 0;
    return `<button class="calendar-day ${countOnDay ? "has-records" : ""} ${model.historyDate === key ? "selected" : ""}" data-action="history-day" data-id="${key}" type="button" aria-label="${key}，完成 ${countOnDay} 项">${day}${countOnDay ? `<small>${countOnDay}</small>` : ""}</button>`;
  }).join("");
  const filtered = model.historyDate ? stats.items.filter(item => item.date === model.historyDate) : stats.items;
  return `<div class="summary-grid"><article class="card summary-card"><span class="muted">本月完成次数</span><span class="value">${stats.items.length}</span></article><article class="card summary-card"><span class="muted">完成天数</span><span class="value">${stats.days.size}</span></article><article class="card summary-card"><span class="muted">当前任务</span><span class="value">${[...model.tasks.values()].filter(task => !task.isDeleted).length}</span></article></div><div class="card"><div class="date-bar"><label>月份<input id="history-month" type="month" value="${escapeHtml(model.selectedMonth)}" /></label><button class="button secondary" data-action="clear-history-filter" type="button">${model.historyDate ? "显示整月" : "整月记录"}</button></div><div class="history-calendar">${["一","二","三","四","五","六","日"].map(day => `<span class="calendar-head">${day}</span>`).join("")}${calendar}</div></div><div class="section-heading"><h2>${model.historyDate ? escapeHtml(model.historyDate) : `${escapeHtml(model.selectedMonth)} 完成记录`}</h2></div><div class="task-list">${filtered.length ? filtered.map(item => `<article class="task-card is-complete"><span class="check-button checked">✓</span><div class="task-main"><div class="task-title">${escapeHtml(item.title || "已删除任务")}</div><div class="task-meta">${escapeHtml(item.date)} · ${item.completedAt ? new Date(item.completedAt).toLocaleTimeString("zh-CN", { hour: "2-digit", minute: "2-digit" }) : "已完成"}</div></div><button class="icon-button" data-action="undo-history" data-id="${escapeHtml(item.taskExternalId)}" data-date="${escapeHtml(item.date)}" aria-label="撤销完成" type="button">↶</button></article>`).join("") : `<div class="card empty-state"><strong>这个时间段还没有完成记录</strong><span>完成任务后，记录会出现在这里。</span></div>`}</div>`;
}
function renderSettings() {
  const categories = [...model.categories.values()].filter(item => !item.isDeleted).sort((a, b) => (a.sortOrder || 0) - (b.sortOrder || 0));
  return `<div class="settings-grid"><article class="card settings-card"><h2>账号与同步</h2><p>当前账号：${escapeHtml(model.user?.email || "未登录")}</p><p class="sync-state">${escapeHtml(model.syncMessage)}</p><p>任务、分类和完成记录通过云端在已登录设备间同步。每个账号的数据相互隔离。</p><button class="button secondary" data-action="sign-out" type="button">退出登录</button></article>
    <article class="card settings-card"><h2>计划数据</h2><p>导入或导出 CSV 任务计划，可与 Android App 手动搬运任务。完成记录仍保存在云端。</p><div class="inline-form"><button class="button secondary" data-action="export-csv" type="button">导出 CSV</button><button class="button secondary" data-action="template-csv" type="button">下载模板</button></div><div class="inline-form"><label class="button secondary">导入 CSV<input id="import-file" type="file" accept=".csv,text/csv" hidden /></label></div><p>CSV 不包含已完成历史；Excel（.xlsx）网页导入暂不支持。</p></article>
    <article class="card settings-card"><h2>任务分类</h2><div class="category-list">${categories.map(category => `<div class="category-row"><i class="dot" style="width:11px;height:11px;background:${escapeHtml(cssColor(category.colorHex || "#FF98A2B3"))}"></i><span class="category-name">${escapeHtml(category.name)}</span><button data-action="edit-category" data-id="${escapeHtml(categoryDocId(category.name))}" type="button">编辑</button><button data-action="delete-category" data-id="${escapeHtml(categoryDocId(category.name))}" type="button">删除</button></div>`).join("") || `<p>还没有自定义分类。</p>`}</div><div class="inline-form"><input id="new-category" maxlength="80" placeholder="新分类名称" /><button class="button secondary" data-action="add-category" type="button">添加</button></div></article>
    <article class="card settings-card"><h2>提醒能力</h2><p>浏览器提醒需要授权；网页保持打开时会检查提醒时间。浏览器关闭后的可靠定时提醒需要额外的推送服务，暂未启用。</p><button class="button secondary" data-action="enable-notifications" type="button">${"Notification" in window && Notification.permission === "granted" ? "提醒权限已开启" : "开启提醒权限"}</button></article></div>`;
}
function render() {
  if (!model.user) return;
  const today = localDateString();
  $("#today-label").textContent = `${weekdayLabel(today)} · ${formatDate(today, { year: "numeric", month: "long", day: "numeric" })}`;
  $("#page-title").textContent = ({ today: "今日安排", plans: "计划日历", history: "完成记录", settings: "设置" })[model.tab];
  document.querySelectorAll(".tab").forEach(tab => tab.classList.toggle("active", tab.dataset.tab === model.tab));
  const content = $("#content");
  content.innerHTML = model.tab === "today" ? renderToday() : model.tab === "plans" ? renderPlans() : model.tab === "history" ? renderHistory() : renderSettings();
}

function showTaskDialog(task = null) {
  const form = $("#task-form");
  form.reset();
  $("#task-error").textContent = "";
  $("#task-dialog-title").textContent = task ? "编辑任务" : "新建任务";
  $("#task-external-id").value = task?.externalId || "";
  $("#task-title").value = task?.title || "";
  $("#task-description").value = task?.description || "";
  $("#task-type").value = task?.type || "daily";
  $("#once-date").value = task?.type === "once" ? task.startDate : model.selectedDate || localDateString();
  $("#task-start").value = task?.startDate || model.selectedDate || localDateString();
  $("#task-end").value = task?.endDate || "";
  $("#repeat-kind").value = task?.repeatKind && task.repeatKind !== "daily" ? task.repeatKind : "weekly";
  $("#interval-days").value = task?.intervalDays || 3;
  $("#monthly-day").value = task?.monthlyDay || 1;
  $("#task-category").innerHTML = `<option value="">未分类</option>${[...model.categories.values()].filter(item => !item.isDeleted).sort((a,b)=>(a.sortOrder||0)-(b.sortOrder||0)).map(category => `<option value="${escapeHtml(category.name)}">${escapeHtml(category.name)}</option>`).join("")}`;
  $("#task-category").value = task?.categoryName || "";
  const weekly = String(task?.weeklyDays || "1,3,5").split(",");
  document.querySelectorAll(".weekday").forEach(input => { input.checked = weekly.includes(input.value); });
  $("#reminder-enabled").checked = !!task?.reminderEnabled;
  $("#reminder-time").value = task?.reminderTime || "09:00";
  $("#reminder-offset").value = String(task?.reminderOffset || 0);
  refreshTaskFields();
  $("#task-dialog").showModal();
  setTimeout(() => $("#task-title").focus(), 50);
}
function refreshTaskFields() {
  const type = $("#task-type").value;
  const repeat = $("#repeat-kind").value;
  $("#once-fields").hidden = type !== "once";
  $("#repeat-fields").hidden = type === "once";
  $("#custom-fields").hidden = type !== "custom";
  $("#weekly-fields").hidden = type !== "custom" || repeat !== "weekly";
  $("#interval-fields").hidden = type !== "custom" || repeat !== "interval";
  $("#monthly-fields").hidden = type !== "custom" || repeat !== "monthly";
}
$("#task-type").addEventListener("change", refreshTaskFields);
$("#repeat-kind").addEventListener("change", refreshTaskFields);
$("#task-form").addEventListener("submit", async event => {
  if (event.submitter?.value === "cancel") return;
  event.preventDefault();
  const title = $("#task-title").value.trim();
  const type = $("#task-type").value;
  const repeatKind = type === "once" ? "once" : type === "daily" ? "daily" : $("#repeat-kind").value;
  const startDate = type === "once" ? $("#once-date").value : $("#task-start").value;
  const endDate = type === "once" ? "" : $("#task-end").value;
  const weeklyDays = [...document.querySelectorAll(".weekday:checked")].map(input => Number(input.value)).sort((a,b)=>a-b).join(",");
  if (!title) return $("#task-error").textContent = "请填写任务名称。";
  if (!startDate) return $("#task-error").textContent = "请选择日期。";
  if (endDate && endDate < startDate) return $("#task-error").textContent = "结束日期不能早于开始日期。";
  if (type === "custom" && repeatKind === "weekly" && !weeklyDays) return $("#task-error").textContent = "请至少选择一个重复星期。";
  const old = model.tasks.get($("#task-external-id").value);
  const ruleChanged = old && (old.type !== type || old.repeatKind !== repeatKind || old.startDate !== startDate || old.endDate !== endDate || old.weeklyDays !== weeklyDays || Number(old.intervalDays) !== Number($("#interval-days").value) || Number(old.monthlyDay) !== Number($("#monthly-day").value));
  const now = Date.now();
  const record = {
    externalId: old?.externalId || newId(),
    title,
    description: $("#task-description").value.trim(),
    categoryName: $("#task-category").value || "",
    type,
    repeatKind,
    startDate,
    endDate: endDate || null,
    ruleEffectiveFrom: ruleChanged ? localDateString() : old?.ruleEffectiveFrom || null,
    intervalDays: Number($("#interval-days").value) || 3,
    weeklyDays: weeklyDays || "1,3,5",
    monthlyDay: Number($("#monthly-day").value) || 1,
    reminderEnabled: $("#reminder-enabled").checked,
    reminderTime: $("#reminder-time").value || "09:00",
    reminderOffset: Number($("#reminder-offset").value) || 0,
    isPaused: !!old?.isPaused,
    isDeleted: false,
    createdAt: old?.createdAt || now,
    updatedAt: now,
  };
  try {
    await setDoc(doc(db, "users", model.user.uid, "tasks", record.externalId), record);
    $("#task-dialog").close();
    showToast(old ? "任务已保存并同步" : "任务已添加并同步");
  } catch (error) { $("#task-error").textContent = friendlyError(error); }
});

function filterCsvFormula(value) {
  const text = String(value ?? "");
  const trimmed = text.trimStart();
  return /^[=+\-@]/.test(trimmed) || /^'[\s]*[=+\-@]/.test(trimmed) ? `'${text}` : text;
}
function csvCell(value) { return `"${filterCsvFormula(value).replaceAll('"', '""')}"`; }
function taskToCsv(task) {
  const oneTime = task.type === "once";
  const typeName = oneTime ? "单次" : task.type === "daily" ? "每日" : task.repeatKind === "weekly" ? "每周" : task.repeatKind === "interval" ? "间隔" : "每月";
  return [task.externalId, task.title, task.description, task.categoryName || "未分类", typeName, oneTime ? "" : task.startDate, task.endDate || "", oneTime ? "" : task.ruleEffectiveFrom || "", oneTime ? task.startDate : "", task.repeatKind === "weekly" ? task.weeklyDays : "", task.repeatKind === "interval" ? task.intervalDays : "", task.repeatKind === "monthly" ? task.monthlyDay : "", !!task.reminderEnabled, task.reminderTime || "09:00", task.reminderOffset || 0, !!task.isPaused];
}
function download(name, text) {
  const blob = new Blob(["\uFEFF", text], { type: "text/csv;charset=utf-8" });
  const url = URL.createObjectURL(blob);
  const link = document.createElement("a");
  link.href = url;
  link.download = name;
  link.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
function exportCsv(template = false) {
  const lines = [HEADERS.map(csvCell).join(",")];
  if (!template) [...model.tasks.values()].filter(task => !task.isDeleted).forEach(task => lines.push(taskToCsv(task).map(csvCell).join(",")));
  download(template ? "DailyPlan-template.csv" : `DailyPlan-${localDateString()}.csv`, lines.join("\r\n") + "\r\n");
}
function parseCsv(text) {
  const rows = [];
  let row = [], cell = "", quoted = false;
  const input = text.replace(/^\uFEFF/, "");
  for (let index = 0; index < input.length; index++) {
    const char = input[index];
    if (quoted) {
      if (char === '"' && input[index + 1] === '"') { cell += '"'; index++; }
      else if (char === '"') quoted = false;
      else cell += char;
    } else if (char === '"' && cell === "") quoted = true;
    else if (char === ",") { row.push(cell); cell = ""; }
    else if (char === "\n" || char === "\r") {
      if (char === "\r" && input[index + 1] === "\n") index++;
      row.push(cell); rows.push(row); row = []; cell = "";
    } else cell += char;
  }
  if (cell || row.length) { row.push(cell); rows.push(row); }
  return rows;
}
function unprotect(value) {
  const text = String(value || "");
  return /^'\s*[=+\-@]/.test(text.trimStart()) ? text.replace(/^\s*'/, "") : text;
}
function parseImportRows(rows) {
  if (!rows.length) throw new Error("文件为空。");
  const headers = rows[0].map(value => value.trim().replace(/^\uFEFF/, ""));
  const index = Object.fromEntries(headers.map((header, i) => [header, i]));
  if (index["任务名称"] == null) throw new Error("CSV 缺少“任务名称”字段。");
  const val = (cells, key) => unprotect(cells[index[key]] ?? "").trim();
  const records = [];
  rows.slice(1).forEach((cells, rowIndex) => {
    if (cells.every(value => !value.trim())) return;
    const typeText = val(cells, "任务类型").toLowerCase();
    const typeMap = { "单次": ["once", "once"], once: ["once", "once"], "每日": ["daily", "daily"], "每天": ["daily", "daily"], daily: ["daily", "daily"], "每周": ["custom", "weekly"], weekly: ["custom", "weekly"], "间隔": ["custom", "interval"], interval: ["custom", "interval"], "每月": ["custom", "monthly"], monthly: ["custom", "monthly"] };
    const pair = typeMap[typeText];
    if (!pair) throw new Error(`第 ${rowIndex + 2} 行：任务类型不正确。`);
    const [type, repeatKind] = pair;
    const date = type === "once" ? val(cells, "执行日期") || val(cells, "开始日期") : val(cells, "开始日期");
    if (!/^\d{4}-\d{2}-\d{2}$/.test(date) || Number.isNaN(new Date(`${date}T12:00:00`).getTime())) throw new Error(`第 ${rowIndex + 2} 行：开始/执行日期应为 YYYY-MM-DD。`);
    const title = val(cells, "任务名称");
    if (!title) throw new Error(`第 ${rowIndex + 2} 行：任务名称不能为空。`);
    const weeklyDays = val(cells, "每周重复日") || "1,3,5";
    const parsedDays = weeklyDays.split(/[，,;；\s]+/).filter(Boolean).map(Number);
    if (repeatKind === "weekly" && (parsedDays.some(day => day < 1 || day > 7) || !parsedDays.length)) throw new Error(`第 ${rowIndex + 2} 行：每周重复日应为 1-7。`);
    const intervalDays = Number(val(cells, "间隔天数") || 1);
    const monthlyDay = Number(val(cells, "每月执行日") || 1);
    if (repeatKind === "interval" && (!Number.isInteger(intervalDays) || intervalDays < 1 || intervalDays > 36500)) throw new Error(`第 ${rowIndex + 2} 行：间隔天数应为 1-36500。`);
    if (repeatKind === "monthly" && (!Number.isInteger(monthlyDay) || monthlyDay < 1 || monthlyDay > 31)) throw new Error(`第 ${rowIndex + 2} 行：每月执行日应为 1-31。`);
    const endDate = val(cells, "结束日期") || null;
    if (endDate && (!/^\d{4}-\d{2}-\d{2}$/.test(endDate) || endDate < date)) throw new Error(`第 ${rowIndex + 2} 行：结束日期无效。`);
    const rawReminder = val(cells, "提醒开关").toLowerCase();
    const record = {
      externalId: val(cells, "任务标识") || newId(),
      title,
      description: unprotect(cells[index["任务备注"]] || ""),
      categoryName: val(cells, "任务分类") === "未分类" ? "" : val(cells, "任务分类"),
      type,
      repeatKind,
      startDate: date,
      endDate: type === "once" ? null : endDate,
      ruleEffectiveFrom: type === "once" ? null : val(cells, "周期规则生效日期") || null,
      weeklyDays: [...new Set(parsedDays)].sort((a,b)=>a-b).join(","),
      intervalDays,
      monthlyDay,
      reminderEnabled: ["true", "yes", "是", "1"].includes(rawReminder),
      reminderTime: val(cells, "提醒时间") || "09:00",
      reminderOffset: Number(val(cells, "提前提醒分钟数") || 0),
      isPaused: ["true", "yes", "是", "1"].includes(val(cells, "暂停状态").toLowerCase()),
      isDeleted: false,
      createdAt: Date.now(),
      updatedAt: Date.now(),
    };
    records.push(record);
  });
  return records;
}
async function importCsv(file) {
  if (!file) return;
  try {
    if (file.size > 24 * 1024 * 1024) throw new Error("文件超过 24 MB。 ");
    const records = parseImportRows(parseCsv(await file.text()));
    if (!records.length) throw new Error("文件中没有可导入的任务。");
    const existingIds = new Set([...model.tasks.keys()]);
    const unique = records.filter(record => !existingIds.has(record.externalId));
    const duplicates = records.length - unique.length;
    if (!unique.length) return showToast(`没有新任务，${duplicates} 项已存在。`);
    if (!confirm(`识别到 ${unique.length} 项新任务${duplicates ? `，${duplicates} 项重复内容将跳过` : ""}。继续导入？`)) return;
    for (const categoryName of [...new Set(unique.map(item => item.categoryName).filter(Boolean))]) {
      const key = categoryDocId(categoryName);
      if (!model.categories.has(key)) {
        await setDoc(doc(db, "users", model.user.uid, "categories", key), {
          name: categoryName, normalizedName: normalizeName(categoryName), colorHex: COLORS[2], sortOrder: model.categories.size, createdAt: Date.now(), updatedAt: Date.now(),
        });
      }
    }
    for (const record of unique) await setDoc(doc(db, "users", model.user.uid, "tasks", record.externalId), record);
    showToast(`已导入 ${unique.length} 项并开始同步。`);
  } catch (error) { showToast(error.message || "导入失败。"); }
}

function updateTask(task) {
  return setDoc(doc(db, "users", model.user.uid, "tasks", task.externalId), { ...task, updatedAt: Date.now() });
}
function updateOccurrence(task, date, isCompleted) {
  const key = occurrenceKey(task.externalId, date);
  const reference = doc(db, "users", model.user.uid, "occurrences", key);
  if (!isCompleted) return writeDeletion("occurrences", key, model.occurrences.get(key)?.updatedAt).then(() => deleteDoc(reference));
  const updatedAt = nextUpdatedAt("occurrences", key, model.occurrences.get(key)?.updatedAt);
  return setDoc(reference, {
    taskExternalId: task.externalId,
    date,
    title: task.title,
    description: task.description || "",
    isCompleted: true,
    completedAt: updatedAt,
    snoozedUntil: null,
    updatedAt,
  });
}
async function addCategory() {
  const input = $("#new-category");
  const name = input.value.trim();
  if (!name) return showToast("请填写分类名称。");
  if (name.length > 80) return showToast("分类名称不能超过 80 个字符。");
  const key = categoryDocId(name);
  if (model.categories.has(key)) return showToast("分类已存在。");
  try {
    const updatedAt = nextUpdatedAt("categories", key);
    await setDoc(doc(db, "users", model.user.uid, "categories", key), {
      name, normalizedName: normalizeName(name), colorHex: COLORS[model.categories.size % COLORS.length], sortOrder: model.categories.size, createdAt: updatedAt, updatedAt,
    });
    input.value = "";
    showToast("分类已添加并同步。");
  } catch (error) { showToast(friendlyError(error)); }
}
async function editCategory(key) {
  const category = model.categories.get(key);
  if (!category) return;
  const name = prompt("修改分类名称", category.name)?.trim();
  if (!name || name === category.name) return;
  const nextKey = categoryDocId(name);
  if (model.categories.has(nextKey)) return showToast("分类已存在。");
  try {
    const updated = { ...category, name, normalizedName: normalizeName(name), updatedAt: nextUpdatedAt("categories", nextKey, category.updatedAt) };
    await setDoc(doc(db, "users", model.user.uid, "categories", nextKey), updated);
    if (nextKey !== key) {
      await writeDeletion("categories", key, category.updatedAt);
      await deleteDoc(doc(db, "users", model.user.uid, "categories", key));
    }
    for (const task of model.tasks.values()) if (task.categoryName === category.name && !task.isDeleted) await updateTask({ ...task, categoryName: name });
    showToast("分类名称已修改并同步。");
  } catch (error) { showToast(friendlyError(error)); }
}
async function deleteCategory(key) {
  const category = model.categories.get(key);
  if (!category || !confirm(`删除“${category.name}”？相关任务会转为未分类。`)) return;
  try {
    for (const task of model.tasks.values()) if (task.categoryName === category.name && !task.isDeleted) await updateTask({ ...task, categoryName: "" });
    await writeDeletion("categories", key, category.updatedAt);
    await deleteDoc(doc(db, "users", model.user.uid, "categories", key));
    showToast("分类已删除，任务和记录保留。");
  } catch (error) { showToast(friendlyError(error)); }
}

async function checkOpenPageReminders() {
  if (!("Notification" in window) || Notification.permission !== "granted" || !model.user) return;
  const today = localDateString();
  const minuteNow = new Date();
  const todayMinute = minuteNow.getHours() * 60 + minuteNow.getMinutes();
  for (const task of model.tasks.values()) {
    if (!task.reminderEnabled || task.isPaused || task.isDeleted) continue;
    const dueDate = task.type === "once" && task.startDate < today ? task.startDate : today;
    if (!occursOn(task, dueDate)) continue;
    const [hour, minute] = String(task.reminderTime || "09:00").split(":").map(Number);
    const targetMinute = hour * 60 + minute - Number(task.reminderOffset || 0);
    const key = `${task.externalId}_${dueDate}_${targetMinute}`;
    if (Math.abs(todayMinute - targetMinute) <= 1 && !currentOccurrence(task, dueDate)?.isCompleted && !model.notificationKeys.has(key)) {
      model.notificationKeys.add(key);
      if (navigator.serviceWorker?.controller) {
        const registration = await navigator.serviceWorker.ready;
        registration.showNotification("每日计划提醒", { body: task.title, icon: "./icon.svg", tag: key }).catch(() => {});
      } else {
        try { new Notification("每日计划提醒", { body: task.title, icon: "./icon.svg" }); } catch { /* Browser notification surface unavailable. */ }
      }
    }
  }
}
setInterval(checkOpenPageReminders, 30000);
document.addEventListener("visibilitychange", () => { if (!document.hidden) checkOpenPageReminders(); });

document.addEventListener("click", async event => {
  const tab = event.target.closest("[data-tab]");
  if (tab) { model.tab = tab.dataset.tab; render(); return; }
  const button = event.target.closest("[data-action]");
  if (!button) return;
  const { action, id, date } = button.dataset;
  try {
    if (action === "sign-out") await signOut(auth);
    else if (action === "filter") { model.categoryFilter = id; render(); }
    else if (action === "edit-task") showTaskDialog(model.tasks.get(id));
    else if (action === "pause-task") { const task = model.tasks.get(id); if (task) { await updateTask({ ...task, isPaused: !task.isPaused, ruleEffectiveFrom: task.isPaused ? localDateString() : task.ruleEffectiveFrom }); showToast(task.isPaused ? "任务已恢复" : "任务已暂停"); } }
    else if (action === "delete-task") { const task = model.tasks.get(id); if (task && confirm(`删除“${task.title}”？历史完成记录会保留。`)) { await updateTask({ ...task, isDeleted: true }); showToast("任务已移入历史记录"); } }
    else if (action === "toggle-complete") { const task = model.tasks.get(id); if (task) await updateOccurrence(task, date, !currentOccurrence(task, date)?.isCompleted); }
    else if (action === "undo-history") { const occurrenceId = occurrenceKey(id, date); const occurrence = model.occurrences.get(occurrenceId); if (occurrence) { await writeDeletion("occurrences", occurrenceId, occurrence.updatedAt); await deleteDoc(doc(db, "users", model.user.uid, "occurrences", occurrenceId)); } }
    else if (action === "history-day") { model.historyDate = model.historyDate === id ? "" : id; render(); }
    else if (action === "clear-history-filter") { model.historyDate = ""; render(); }
    else if (action === "add-category") await addCategory();
    else if (action === "edit-category") await editCategory(id);
    else if (action === "delete-category") await deleteCategory(id);
    else if (action === "export-csv") exportCsv(false);
    else if (action === "template-csv") exportCsv(true);
    else if (action === "enable-notifications") {
      if (!("Notification" in window)) showToast("当前浏览器不支持通知。");
      else if (Notification.permission === "granted") showToast("提醒权限已开启。");
      else if (Notification.permission === "denied") showToast("通知权限已被拒绝，请到浏览器设置中开启。");
      else { const result = await Notification.requestPermission(); showToast(result === "granted" ? "提醒权限已开启。" : "未获得通知授权。"); }
    }
  } catch (error) { showToast(friendlyError(error)); }
});
document.addEventListener("change", event => {
  if (event.target.id === "plan-date") { model.selectedDate = event.target.value || localDateString(); render(); }
  if (event.target.id === "history-month") { model.selectedMonth = event.target.value || localDateString().slice(0, 7); model.historyDate = ""; render(); }
  if (event.target.id === "import-file") importCsv(event.target.files?.[0]);
});
$("#add-task").addEventListener("click", () => showTaskDialog());

window.addEventListener("unhandledrejection", event => {
  showToast(friendlyError(event.reason));
});
