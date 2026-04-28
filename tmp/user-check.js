
  const TOKEN_KEY = "authToken";
  const ROLE_KEY = "authRole";
  const token = localStorage.getItem(TOKEN_KEY);
  const role = localStorage.getItem(ROLE_KEY);
  if (!token || role !== "ROLE_USER") {
    location.href = "/login.html?role=user&next=/user.html";
  }

  let activeSource = null;
  let activeAssistantTextNode = null;
  let editingAppointmentId = null;
  let currentAppointments = [];

  function authHeaders(json) {
    const headers = { Authorization: "Bearer " + localStorage.getItem(TOKEN_KEY) };
    if (json) headers["Content-Type"] = "application/json";
    return headers;
  }

  function esc(value) {
    const div = document.createElement("div");
    div.textContent = value == null ? "" : String(value);
    return div.innerHTML;
  }

  function setStatus(id, text, cls = "") {
    const element = document.getElementById(id);
    element.textContent = text;
    element.className = "tag " + cls;
  }

  function isValidEmail(value) {
    return /^[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}$/.test(String(value || "").trim());
  }

  function isPastAppointment(dateText, timeSlot) {
    if (!dateText || !timeSlot) return false;
    const start = String(timeSlot).split("-")[0];
    const startAt = new Date(dateText + "T" + start + ":00");
    return Number.isFinite(startAt.getTime()) && startAt.getTime() < Date.now();
  }

  function formatAssistantText(text) {
    const normalized = String(text || "")
      .replace(/\r\n/g, "\n")
      .replace(/^\s*##+\s*/gm, "")
      .replace(/^\s*[-*]\s+/gm, "")
      .replace(/\*\*(.*?)\*\*/g, "$1")
      .replace(/`([^`]+)`/g, "$1")
      .replace(/^\s*>\s*/gm, "")
      .replace(/\n{3,}/g, "\n\n")
      .trim();
    if (!normalized) return "...";
    return normalized
      .split("\n")
      .map((line) => line.trimEnd())
      .map((line) => esc(line))
      .join("<br>");
  }

  function appendMessage(sender, text, type) {
    const row = document.createElement("div");
    row.className = "message-row " + type;

    const avatar = document.createElement("div");
    avatar.className = "message-avatar";
    avatar.textContent = type === "user" ? "U" : "AI";

    const bubble = document.createElement("div");
    bubble.className = "message-bubble";

    const roleNode = document.createElement("div");
    roleNode.className = "message-role";
    roleNode.textContent = sender;

    const body = document.createElement("div");
    body.className = "message-text";
    body.innerHTML = type === "assistant" ? formatAssistantText(text) : esc(text);

    bubble.appendChild(roleNode);
    bubble.appendChild(body);
    row.appendChild(avatar);
    row.appendChild(bubble);

    const box = document.getElementById("chatBox");
    box.appendChild(row);
    box.scrollTop = box.scrollHeight;
    return body;
  }

  function scrollChatToBottom() {
    const box = document.getElementById("chatBox");
    box.scrollTop = box.scrollHeight;
  }

  function stopStreaming(markStopped) {
    if (activeSource) {
      activeSource.close();
      activeSource = null;
    }
    document.getElementById("stopBtn").disabled = true;
    if (markStopped && activeAssistantTextNode) {
      activeAssistantTextNode.innerHTML += "<br><span class='message-stopped'>已停止生成</span>";
      scrollChatToBottom();
    }
    activeAssistantTextNode = null;
  }

  async function api(url, options = {}) {
    const res = await fetch(url, options);
    const type = res.headers.get("content-type") || "";
    const payload = type.includes("application/json") ? await res.json() : await res.text();
    return { ok: res.ok, status: res.status, data: payload };
  }

  function switchPanel(name) {
    document.querySelectorAll(".panel").forEach((panel) => panel.classList.toggle("active", panel.id === name));
    document.querySelectorAll(".menu-btn").forEach((button) => button.classList.toggle("active", button.dataset.panel === name));
  }

  function appointmentModeLabel() {
    return editingAppointmentId ? "改期" : "预约";
  }

  function setAppointmentMode(editing, item) {
    editingAppointmentId = editing && item ? String(item.id) : null;
    document.getElementById("appointmentFormTitle").textContent = editing ? "改期预约" : "提交预约";
    document.getElementById("submitAppointment").textContent = editing ? "保存改期" : "提交预约";
    document.getElementById("cancelEditAppointment").style.display = editing ? "inline-flex" : "none";
    const hint = document.getElementById("appointmentEditHint");
    if (editing && item) {
      hint.style.display = "block";
      hint.textContent = "当前正在改期：编号 " + item.id + "，原时间 " + item.date + " " + item.time + "，专家 " + item.expert;
    } else {
      hint.style.display = "none";
      hint.textContent = "";
    }
  }

  function clearAppointmentSuggestions() {
    const suggest = document.getElementById("apSuggest");
    const actions = document.getElementById("apSuggestActions");
    suggest.style.display = "none";
    suggest.textContent = "";
    actions.style.display = "none";
    actions.innerHTML = "";
    actions.dataset.suggestions = "[]";
  }

  function renderAppointmentSuggestions(suggestions) {
    const suggest = document.getElementById("apSuggest");
    const actions = document.getElementById("apSuggestActions");
    if (!Array.isArray(suggestions) || !suggestions.length) {
      clearAppointmentSuggestions();
      return;
    }
    suggest.style.display = "block";
    suggest.textContent = appointmentModeLabel() + "时段冲突，请直接选择一个建议时段。";
    actions.style.display = "flex";
    actions.dataset.suggestions = JSON.stringify(suggestions);
    actions.innerHTML = suggestions.map((item, index) =>
      "<button class='btn btn-secondary suggestion-btn' data-suggestion-index='" + index + "' type='button'>"
      + esc(item.date) + " " + esc(item.time) + " (" + esc(item.expert) + ")</button>"
    ).join("");
  }

  function resetAppointmentForm(keepIdentity = false) {
    if (!keepIdentity) {
      document.getElementById("apName").value = "";
      document.getElementById("apEmail").value = "";
    }
    document.getElementById("apDate").value = "";
    document.getElementById("apTime").value = "";
    document.getElementById("apExpert").value = "";
    document.getElementById("apReason").value = "";
    clearAppointmentSuggestions();
    setAppointmentMode(false);
    setStatus("apStatus", "待提交");
  }

  function fillAppointmentForm(item) {
    document.getElementById("apName").value = item.name || "";
    document.getElementById("apEmail").value = item.email || "";
    document.getElementById("apDate").value = item.date || "";
    document.getElementById("apTime").value = item.time || "";
    document.getElementById("apExpert").value = item.expert || "";
    document.getElementById("apReason").value = item.reason || "";
    clearAppointmentSuggestions();
    setAppointmentMode(true, item);
    setStatus("apStatus", "请选择新的日期和时间", "warn");
    switchPanel("bookPanel");
  }

  document.querySelectorAll(".menu-btn").forEach((button) => {
    button.addEventListener("click", () => switchPanel(button.dataset.panel));
  });

  document.getElementById("whoami").textContent = "已登录角色: " + role;
  document.getElementById("logoutBtn").addEventListener("click", () => {
    stopStreaming(false);
    localStorage.removeItem(TOKEN_KEY);
    localStorage.removeItem(ROLE_KEY);
    location.href = "/index.html";
  });
  document.getElementById("stopBtn").addEventListener("click", () => stopStreaming(true));

  async function loadDocs() {
    const statusRes = await api("/rag/status", { headers: authHeaders() });
    if (statusRes.ok && statusRes.data) {
      document.getElementById("statDocs").textContent = String(statusRes.data.documents || 0);
    }

    const res = await api("/rag/documents", { headers: authHeaders() });
    const rows = document.getElementById("docRows");
    if (!res.ok) {
      rows.innerHTML = "<tr><td colspan='4' class='empty'>加载失败</td></tr>";
      return;
    }
    const docs = res.data || [];
    if (!docs.length) {
      rows.innerHTML = "<tr><td colspan='4' class='empty'>暂无文档</td></tr>";
      return;
    }
    rows.innerHTML = docs.map((doc) => "<tr><td>" + esc(doc.id) + "</td><td>" + esc(doc.filename) + "</td><td>" + esc(doc.chunks) + "</td><td><button class='btn btn-secondary' data-delete='" + esc(doc.id) + "' type='button'>删除</button></td></tr>").join("");
  }

  document.getElementById("docRows").addEventListener("click", async (event) => {
    const id = event.target.getAttribute("data-delete");
    if (!id) return;
    const res = await api("/rag/documents/" + id, { method: "DELETE", headers: authHeaders() });
    if (res.ok) loadDocs();
  });

  document.getElementById("kbUpload").addEventListener("click", async () => {
    const file = document.getElementById("kbFile").files[0];
    if (!file) {
      setStatus("kbStatus", "请先选择文件", "err");
      return;
    }
    setStatus("kbStatus", "上传中");
    const form = new FormData();
    form.append("file", file);
    const res = await fetch("/rag/upload", { method: "POST", headers: { Authorization: "Bearer " + token }, body: form });
    setStatus("kbStatus", res.ok ? "上传成功" : "上传失败", res.ok ? "ok" : "err");
    if (res.ok) loadDocs();
  });

  async function loadAppointments() {
    const res = await api("/api/appointments", { headers: authHeaders() });
    const rows = document.getElementById("appointmentRows");
    if (!res.ok) {
      currentAppointments = [];
      rows.innerHTML = "<tr><td colspan='6' class='empty'>预约加载失败</td></tr>";
      return;
    }

    const items = res.data || [];
    currentAppointments = items;
    document.getElementById("statAppointments").textContent = String(items.length);
    document.getElementById("statBooked").textContent = String(items.filter((item) => item.status === "BOOKED").length);
    document.getElementById("statCanceled").textContent = String(items.filter((item) => item.status === "CANCELED").length);

    if (!items.length) {
      rows.innerHTML = "<tr><td colspan='6' class='empty'>暂无预约记录</td></tr>";
      return;
    }
    rows.innerHTML = items.map((item) => {
      const action = item.status === "BOOKED"
        ? "<button class='btn btn-secondary' data-reschedule='" + esc(item.id) + "' type='button'>改期</button> <button class='btn btn-danger' data-cancel='" + esc(item.id) + "' type='button'>取消</button>"
        : "";
      return "<tr><td>" + esc(item.id) + "</td><td>" + esc(item.date) + "</td><td>" + esc(item.time) + "</td><td>" + esc(item.expert) + "</td><td>" + esc(item.status) + "</td><td>" + action + "</td></tr>";
    }).join("");
  }

  document.getElementById("refreshAppointments").addEventListener("click", loadAppointments);
  document.getElementById("cancelEditAppointment").addEventListener("click", () => resetAppointmentForm(true));
  document.getElementById("apSuggestActions").addEventListener("click", (event) => {
    const button = event.target.closest("[data-suggestion-index]");
    if (!button) return;
    let suggestions = [];
    try {
      suggestions = JSON.parse(event.currentTarget.dataset.suggestions || "[]");
    } catch (error) {
      suggestions = [];
    }
    const picked = suggestions[Number(button.dataset.suggestionIndex)];
    if (!picked) return;
    document.getElementById("apDate").value = picked.date || "";
    document.getElementById("apTime").value = picked.time || "";
    document.getElementById("apExpert").value = picked.expert || "";
    setStatus("apStatus", "已选择建议时段", "warn");
  });

  document.getElementById("appointmentRows").addEventListener("click", async (event) => {
    const cancelId = event.target.getAttribute("data-cancel");
    const rescheduleId = event.target.getAttribute("data-reschedule");
    if (cancelId) {
      const res = await api("/api/appointments/" + cancelId + "/cancel", { method: "PATCH", headers: authHeaders() });
      if (res.ok) loadAppointments();
      return;
    }
    if (rescheduleId) {
      const target = currentAppointments.find((item) => String(item.id) === String(rescheduleId));
      if (!target) return;
      fillAppointmentForm(target);
    }
  });

  document.getElementById("submitAppointment").addEventListener("click", async () => {
    const payload = {
      name: document.getElementById("apName").value.trim(),
      email: document.getElementById("apEmail").value.trim(),
      date: document.getElementById("apDate").value.trim(),
      time: document.getElementById("apTime").value.trim(),
      expert: document.getElementById("apExpert").value.trim(),
      reason: document.getElementById("apReason").value.trim() || "在线咨询"
    };
    if (!payload.name || !payload.email || !payload.date || !payload.time) {
      setStatus("apStatus", "请补全姓名、邮箱、日期和时间", "err");
      return;
    }
    if (!isValidEmail(payload.email)) {
      setStatus("apStatus", "请输入正确的邮箱格式", "err");
      return;
    }
    if (isPastAppointment(payload.date, payload.time)) {
      setStatus("apStatus", appointmentModeLabel() + "时间不能早于当前时间", "err");
      return;
    }
    setStatus("apStatus", editingAppointmentId ? "改期提交中" : "提交中");
    clearAppointmentSuggestions();
    const isEditing = !!editingAppointmentId;
    const res = await api(
      isEditing ? "/api/appointments/" + editingAppointmentId + "/reschedule" : "/api/appointments",
      { method: isEditing ? "PUT" : "POST", headers: authHeaders(true), body: JSON.stringify(payload) }
    );
    if (res.ok) {
      setStatus("apStatus", isEditing ? "改期成功" : "预约成功", "ok");
      await loadAppointments();
      resetAppointmentForm(true);
      return;
    }
    setStatus("apStatus", isEditing ? "改期失败" : "预约失败", "err");
    const suggest = document.getElementById("apSuggest");
    if (typeof res.data === "string" && res.data) {
      suggest.style.display = "block";
      suggest.textContent = "失败原因: " + res.data;
    } else if (res.data && res.data.message) {
      suggest.style.display = "block";
      suggest.textContent = "失败原因: " + res.data.message;
    }
    if (res.status === 409 && res.data && res.data.suggestions) {
      renderAppointmentSuggestions(res.data.suggestions);
    }
  });

  document.getElementById("msg").addEventListener("keydown", (event) => {
    if (event.key === "Enter" && !event.shiftKey) {
      event.preventDefault();
      document.getElementById("chatForm").requestSubmit();
    }
  });

  document.getElementById("chatForm").addEventListener("submit", (event) => {
    event.preventDefault();
    const input = document.getElementById("msg");
    const message = input.value.trim();
    if (!message) return;

    stopStreaming(false);
    appendMessage("用户", message, "user");
    input.value = "";

    const target = document.getElementById("useRag").checked ? "/rag/stream" : "/ai/bailian/agent/stream";
    activeAssistantTextNode = appendMessage("助手", "...", "assistant");
    document.getElementById("stopBtn").disabled = false;

    let reply = "";
    activeSource = new EventSource(target + "?message=" + encodeURIComponent(message) + "&access_token=" + encodeURIComponent(token));

    const paint = () => {
      if (activeAssistantTextNode) {
        activeAssistantTextNode.innerHTML = formatAssistantText(reply);
        scrollChatToBottom();
      }
    };

    activeSource.addEventListener("chunk", (e) => {
      reply += e.data || "";
      paint();
    });

    activeSource.addEventListener("model_error", (e) => {
      reply = e.data || "对话失败";
      paint();
      stopStreaming(false);
    });

    activeSource.addEventListener("done", () => {
      if (!reply.trim() && activeAssistantTextNode) {
        activeAssistantTextNode.innerHTML = "已完成";
      }
      stopStreaming(false);
    });

    activeSource.onerror = () => {
      if (!reply.trim() && activeAssistantTextNode) {
        activeAssistantTextNode.innerHTML = "服务暂时不可用";
      }
      stopStreaming(false);
    };
  });

  loadDocs();
  loadAppointments();
  resetAppointmentForm();
