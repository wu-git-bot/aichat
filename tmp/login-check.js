
  const TOKEN_KEY = "authToken";
  const ROLE_KEY = "authRole";
  const params = new URLSearchParams(location.search);
  const role = (params.get("role") || "user").toLowerCase();
  const next = params.get("next");

  const roleMeta = {
    user: { label: "用户", username: "user", landing: "/user.html", role: "ROLE_USER" },
    expert: { label: "专家", username: "linyu", landing: "/expert.html", role: "ROLE_EXPERT" },
    admin: { label: "管理员", username: "admin", landing: "/admin.html", role: "ROLE_ADMIN" }
  };

  const current = roleMeta[role] || roleMeta.user;
  document.getElementById("roleText").textContent = "当前角色：" + current.label;
  document.getElementById("username").value = current.username;
  document.getElementById("chip-" + role).classList.add("active");

  function targetByRole(roleName) {
    if (roleName === "ROLE_ADMIN") return "/admin.html";
    if (roleName === "ROLE_EXPERT") return "/expert.html";
    return "/user.html";
  }

  async function login() {
    const username = document.getElementById("username").value.trim();
    const password = document.getElementById("password").value.trim();
    const status = document.getElementById("status");
    if (!username || !password) {
      status.textContent = "请输入用户名和密码";
      return;
    }

    status.textContent = "登录中...";
    try {
      const res = await fetch("/auth/login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username, password })
      });
      if (!res.ok) throw new Error("用户名或密码错误");
      const data = await res.json();
      if (data.role !== current.role) {
        localStorage.removeItem(TOKEN_KEY);
        localStorage.removeItem(ROLE_KEY);
        throw new Error("当前入口仅允许" + current.label + "登录");
      }
      localStorage.setItem(TOKEN_KEY, data.token);
      localStorage.setItem(ROLE_KEY, data.role);
      status.textContent = "登录成功，正在进入工作台...";
      setTimeout(() => {
        location.href = next || targetByRole(data.role) || current.landing;
      }, 220);
    } catch (e) {
      status.textContent = e.message || "登录失败";
    }
  }

  document.getElementById("loginBtn").addEventListener("click", login);
  document.addEventListener("keydown", (event) => {
    if (event.key === "Enter") login();
  });
