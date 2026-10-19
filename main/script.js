const API_BASE = "https://ai-4-0-7-i-r-a-i-1.onrender.com";

const login = document.getElementById("loginForm");
const register = document.getElementById("registerForm");
const toggle = document.getElementById("toggleForm");
const message = document.getElementById("message");

toggle.addEventListener("click", () => {
    const creating = register.hidden;
    register.hidden = !creating;
    login.hidden = creating;

    toggle.textContent = creating
        ? "Already have an account? Log in"
        : "Don't have an account? Register";

    message.textContent = "";
});

register.addEventListener("submit", async (event) => {
    event.preventDefault();

    const email = document.getElementById("newEmail").value;
    const password = document.getElementById("newPassword").value;
    const confirm = document.getElementById("newPasswordConfirm").value;

    if (password !== confirm) {
        message.textContent = "Passwords do not match!";
        return;
    }

    try {
        const response = await fetch(`${API_BASE}/api/register`, {
            method: "POST",
            credentials: "include",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({
                email,
                password,
                confirmPassword: confirm
            })
        });

        const data = await response.json();

        message.textContent = data.message || data.error ||
            (response.ok ? "Account created successfully!" : "Registration failed.");

        if (response.ok) {
            register.reset();
            register.hidden = true;
            login.hidden = false;
            toggle.textContent = "Create an account";
        }
    } catch (error) {
        console.error(error);
        message.textContent = "Cannot connect to login server. Check your connection.";
    }
});

login.addEventListener("submit", async (event) => {
    event.preventDefault();

    const email = document.getElementById("email").value;
    const password = document.getElementById("password").value;

    try {
        const response = await fetch(`${API_BASE}/api/login`, {
            method: "POST",
            credentials: "include",
            headers: { "Content-Type": "application/json" },
            body: JSON.stringify({ email, password })
        });

        const data = await response.json();

        if (response.ok) {
            window.location.href = "home.html";
        } else {
            message.textContent = data.message || data.error || "Login failed.";
        }
    } catch (error) {
        console.error(error);
        message.textContent = "Cannot connect to login server. Check your connection.";
    }
});